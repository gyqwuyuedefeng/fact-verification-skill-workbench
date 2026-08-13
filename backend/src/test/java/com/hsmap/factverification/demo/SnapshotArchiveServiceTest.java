package com.hsmap.factverification.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.shared.ServiceException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * 被测试对象：SnapshotArchiveService 的受控 ZIP 边界、manifest 校验及 DemoStateRepository 固定 SQL。
 * 测试目的：证明不可信快照在接触正式数据库和运行目录前完成路径、容量、白名单与摘要校验。
 * 覆盖范围：三重容量上限、路径攻击、重复条目、版本/行数/摘要完整性、活动状态和敏感文件排除。
 * 前置条件：所有文件均位于 JUnit 临时目录，数据库仓储和状态服务使用测试替身，不连接共享数据库。
 */
class SnapshotArchiveServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path temporaryRoot;

    /**
     * 测试场景：仓储导出与导入 claim 表。
     * 前置条件：调用方只能传 SnapshotTable 枚举，JdbcTemplate 使用 Mock 观察最终 SQL。
     * 期望结果：导出采用 brief 指定的 to_jsonb 查询，导入采用 claim 复合行类型回填。
     * 断言重点：schema 和表名均来自生产白名单，SQL 中不存在请求参数占位的表标识符。
     */
    @Test
    void buildsExportAndImportSqlOnlyFromSnapshotTableEnum() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DemoStateRepository repository = new DemoStateRepository(jdbcTemplate);

        repository.exportRows(SnapshotTable.CLAIM, row -> {});
        repository.insertRow(SnapshotTable.CLAIM, "{\"id\":\"00000000-0000-0000-0000-000000000001\"}");

        verify(jdbcTemplate)
                .query(
                        org.mockito.ArgumentMatchers.eq(
                                "select to_jsonb(row_value)::text from test.claim row_value order by id"),
                        org.mockito.ArgumentMatchers.any(RowCallbackHandler.class));
        verify(jdbcTemplate)
                .update(
                        org.mockito.ArgumentMatchers.eq(
                                "insert into test.claim select imported.* from jsonb_populate_record(null::test.claim,"
                                        + " ?::jsonb) imported"),
                        org.mockito.ArgumentMatchers.eq("{\"id\":\"00000000-0000-0000-0000-000000000001\"}"));
    }

    /**
     * 测试场景：上传的原始 ZIP 在读取完成前超过配置的 200 MB 等价上限。
     * 前置条件：测试通过缩小上限的配置替身避免构造 200 MB 内存对象。
     * 期望结果：服务立即拒绝，并且不会进入数据库导入。
     * 断言重点：原始压缩包限制独立于 entry 数量和展开体积限制生效。
     */
    @Test
    void rejectsRawArchiveBeyondConfiguredLimitBeforeImport() {
        DemoStateRepository repository = mock(DemoStateRepository.class);
        SnapshotArchiveService archive = archive(repository, mock(DemoStateService.class), 32, 2_000, 500_000_000);

        assertThatThrownBy(() -> archive.importFrom(new ByteArrayInputStream(new byte[33]), "导入快照"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("压缩包大小超过限制");

        verify(repository, never())
                .insertRow(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * 测试场景：ZIP 包含第 2,001 个 entry。
     * 前置条件：entry 均为空文件，避免用展开体积间接触发失败。
     * 期望结果：第 2,001 个条目被拒绝。
     * 断言重点：entry 计数包含所有文件条目，不能用大量空文件耗尽 inode。
     */
    @Test
    void rejectsTwoThousandAndFirstEntry() throws Exception {
        List<ArchiveEntry> entries = new ArrayList<>();
        for (int index = 0; index < 2_001; index++) {
            entries.add(new ArchiveEntry("files/uploads/task/file-" + index, new byte[0]));
        }
        Path zip = writeZip(entries);

        assertThatThrownBy(() -> archive(mock(DemoStateRepository.class), mock(DemoStateService.class))
                        .validateAndStage(zip))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("文件数量超过限制");
    }

    /**
     * 测试场景：ZIP 展开后的累计字节数超过配置的 500 MB 等价上限。
     * 前置条件：测试把展开上限缩小到 64 字节，条目本身只写 65 字节。
     * 期望结果：读取当前条目时失败，不创建任何正式运行文件。
     * 断言重点：限制按实际流式展开字节累计，而不是只相信可伪造的 ZIP 元数据。
     */
    @Test
    void rejectsExpandedDataBeyondConfiguredLimit() throws Exception {
        Path zip = writeZip(List.of(new ArchiveEntry("files/uploads/task/material.bin", new byte[65])));

        assertThatThrownBy(() -> archive(
                                mock(DemoStateRepository.class), mock(DemoStateService.class), 1_000_000, 2_000, 64)
                        .validateAndStage(zip))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("展开大小超过限制");
    }

    /**
     * 测试场景：ZIP entry 使用绝对路径、父级穿越、反斜杠或未知根目录。
     * 前置条件：每次构造一个真实 ZIP entry，攻击路径在 manifest 解析前出现。
     * 期望结果：所有路径都以非法文件路径业务异常拒绝。
     * 断言重点：任何输入路径都不能解析到本次 .demo-import 操作目录之外，也不能扩展归档根集合。
     */
    @Test
    void rejectsAbsoluteTraversalBackslashAndUnknownRootEntries() throws Exception {
        List<String> maliciousPaths = List.of(
                "/etc/passwd",
                "files/uploads/../../application.yml",
                "files\\uploads\\..\\application.yml",
                "config/application.yml");

        for (String maliciousPath : maliciousPaths) {
            Path zip = writeZip(List.of(new ArchiveEntry(maliciousPath, "secret".getBytes(StandardCharsets.UTF_8))));
            assertThatThrownBy(() -> archive(mock(DemoStateRepository.class), mock(DemoStateService.class))
                            .validateAndStage(zip))
                    .as("攻击路径应被拒绝：%s", maliciousPath)
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("非法文件路径");
        }
    }

    /**
     * 测试场景：两个不同 entry 名称规范化后落到同一暂存文件。
     * 前置条件：ZIP API 不允许完全同名 entry，因此使用包含 ./ 的等价路径模拟歧义覆盖。
     * 期望结果：第二个规范化重复条目被拒绝。
     * 断言重点：校验不能让后写 entry 覆盖先写内容并绕过 manifest 摘要。
     */
    @Test
    void rejectsDuplicateNormalizedEntries() throws Exception {
        Path zip = writeZip(List.of(
                new ArchiveEntry("files/uploads/task/./material.txt", "first".getBytes(StandardCharsets.UTF_8)),
                new ArchiveEntry("files/uploads/task/material.txt", "second".getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> archive(mock(DemoStateRepository.class), mock(DemoStateService.class))
                        .validateAndStage(zip))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("重复文件");
    }

    /**
     * 测试场景：ZIP 中的 Unix mode 明确声明一个符号链接 entry。
     * 前置条件：使用 Commons Compress 写入真实的 symlink mode，而不是仅用文件名模拟。
     * 期望结果：在展开目标文件前拒绝归档。
     * 断言重点：导入器必须依据可信库解析 Unix mode，不能把链接当普通文件。
     */
    @Test
    void rejectsUnixSymlinkZipEntry() throws Exception {
        Path zip = Files.createTempFile(temporaryRoot, "symlink-snapshot-", ".zip");
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(zip)) {
            ZipArchiveEntry entry = new ZipArchiveEntry("files/uploads/task/material-link");
            entry.setUnixMode(UnixStat.LINK_FLAG | 0777);
            output.putArchiveEntry(entry);
            output.write("../../outside".getBytes(StandardCharsets.UTF_8));
            output.closeArchiveEntry();
        }

        assertThatThrownBy(() -> archive(mock(DemoStateRepository.class), mock(DemoStateService.class))
                        .validateAndStage(zip))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("符号链接");
    }

    /**
     * 测试场景：攻击者预先把 storageRoot/.demo-import 建成指向根外的符号链接。
     * 前置条件：平台支持创建符号链接，根外目标是 JUnit 临时目录。
     * 期望结果：还未复制 ZIP 时就拒绝操作。
     * 断言重点：任何已有的中间路径都不得通过 symlink 逃离本次 storageRoot。
     */
    @Test
    void rejectsSymlinkInDemoImportIntermediatePath() throws Exception {
        Path storage = temporaryRoot.resolve("storage");
        Path outside = temporaryRoot.resolve("outside");
        Files.createDirectories(storage);
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(storage.resolve(".demo-import"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
            org.junit.jupiter.api.Assumptions.abort("当前文件系统不支持测试符号链接");
        }
        Path zip = writeZip(validArchiveEntries(SnapshotManifest.FORMAT_VERSION));

        assertThatThrownBy(() -> archive(mock(DemoStateRepository.class), mock(DemoStateService.class))
                        .validateAndStage(zip))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("非法暂存路径");
        assertThat(outside).isEmptyDirectory();
    }

    /**
     * 测试场景：manifest 版本不是唯一支持的 v1。
     * 前置条件：七张表文件和摘要均完整，仅替换 formatVersion。
     * 期望结果：版本校验失败且数据库保持未触碰。
     * 断言重点：导入端失败关闭，不能猜测未来格式的字段含义。
     */
    @Test
    void rejectsUnsupportedManifestVersion() throws Exception {
        Path zip = writeZip(validArchiveEntries("fact-verification-demo-state/v2"));

        assertThatThrownBy(() -> archive(mock(DemoStateRepository.class), mock(DemoStateService.class))
                        .validateAndStage(zip))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("快照版本不受支持");
    }

    /**
     * 测试场景：manifest 声明了七张表，但归档缺少其中一个 JSONL 文件。
     * 前置条件：manifest 本身仍保留缺失文件的行数和摘要声明。
     * 期望结果：完整性校验拒绝归档。
     * 断言重点：不能把缺失表解释为空表，否则会静默生成不完整比赛状态。
     */
    @Test
    void rejectsMissingDeclaredTableFile() throws Exception {
        Map<String, byte[]> entries = validArchiveEntries(SnapshotManifest.FORMAT_VERSION);
        entries.remove("tables/claim.jsonl");
        Path zip = writeZip(entries);

        assertThatThrownBy(() -> archive(mock(DemoStateRepository.class), mock(DemoStateService.class))
                        .validateAndStage(zip))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("快照文件缺失");
    }

    /**
     * 测试场景：表文件内容在 manifest 生成后被修改。
     * 前置条件：归档结构、版本和行数仍合法，只有 SHA-256 不匹配。
     * 期望结果：摘要校验失败。
     * 断言重点：导入不得接收被替换或传输损坏的 JSONL 数据。
     */
    @Test
    void rejectsTableSha256Mismatch() throws Exception {
        Map<String, byte[]> entries = validArchiveEntries(SnapshotManifest.FORMAT_VERSION);
        byte[] original = "{\"id\":\"original\"}\n".getBytes(StandardCharsets.UTF_8);
        SnapshotManifest base = OBJECT_MAPPER.readValue(entries.get("manifest.json"), SnapshotManifest.class);
        Map<String, SnapshotManifest.TableEntry> tables = new LinkedHashMap<>(base.tables());
        tables.put("claim", new SnapshotManifest.TableEntry(1, sha256(original)));
        entries.put(
                "manifest.json",
                OBJECT_MAPPER.writeValueAsBytes(
                        new SnapshotManifest(base.formatVersion(), base.createdAt(), tables, base.files())));
        entries.put("tables/claim.jsonl", "{\"id\":\"tampered\"}\n".getBytes(StandardCharsets.UTF_8));
        Path zip = writeZip(entries);

        assertThatThrownBy(() -> archive(mock(DemoStateRepository.class), mock(DemoStateService.class))
                        .validateAndStage(zip))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("SHA-256");
    }

    /**
     * 测试场景：manifest 文件清单使用 /uploads/... 绝对形式。
     * 前置条件：ZIP 内的实际 entry 仍位于合法 files/uploads 根。
     * 期望结果：manifest 路径在拼接 files/ 之前直接被拒绝。
     * 断言重点：不得把前导斜杠解释成可归一化的相对路径。
     */
    @Test
    void rejectsAbsoluteManifestFilePath() throws Exception {
        Map<String, byte[]> entries = validArchiveEntries(SnapshotManifest.FORMAT_VERSION);
        byte[] material = "material".getBytes(StandardCharsets.UTF_8);
        entries.put("files/uploads/task/material.md", material);
        SnapshotManifest base = OBJECT_MAPPER.readValue(entries.get("manifest.json"), SnapshotManifest.class);
        entries.put(
                "manifest.json",
                OBJECT_MAPPER.writeValueAsBytes(new SnapshotManifest(
                        base.formatVersion(),
                        base.createdAt(),
                        base.tables(),
                        List.of(new SnapshotManifest.FileEntry("/uploads/task/material.md", 8, sha256(material))))));

        assertThatThrownBy(() -> archive(mock(DemoStateRepository.class), mock(DemoStateService.class))
                        .validateAndStage(writeZip(entries)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("非法文件路径");
    }

    /**
     * 测试场景：单条 JSONL 远大于当前业务字段规模，但总展开体积仍未超限。
     * 前置条件：通过可控 helper 把单行上限缩小为 32 字节，避免构造百 MB 对象。
     * 期望结果：读流越过第 32 字节时立即抛业务异常。
     * 断言重点：拒绝必须发生在 readLine/Jackson tree 聚合整行之前。
     */
    @Test
    void rejectsJsonLineBeforeAggregatingBeyondHardLimit() throws Exception {
        Path jsonl = temporaryRoot.resolve("oversized.jsonl");
        Files.writeString(jsonl, "{\"payload\":\"" + "x".repeat(64) + "\"}\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SnapshotArchiveService.readUtf8Lines(jsonl, 32, line -> {}))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("JSONL 单行大小超过限制");
    }

    /**
     * 测试场景：manifest 超出硬上限时不交给 Jackson 聚合。
     * 前置条件：通过可控 helper 使用 16 字节上限验证生产相同的读流分支。
     * 期望结果：只读取上限加一的有界字节后拒绝。
     * 断言重点：即使 ZIP 总展开上限很大，manifest 仍有独立小内存边界。
     */
    @Test
    void rejectsManifestBeforeReadingBeyondHardLimit() throws Exception {
        Path manifest = temporaryRoot.resolve("oversized-manifest.json");
        Files.writeString(manifest, "x".repeat(32), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SnapshotArchiveService.readLimitedBytes(manifest, 16))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("manifest 大小超过限制");
    }

    /**
     * 测试场景：活动检查后普通业务新建了未被当前数据库快照引用的文件。
     * 前置条件：verification_task 查询先固定当前空视图，随后模拟普通业务提交新行并写入附件。
     * 期望结果：导出使用 REPEATABLE_READ 只读事务，且 ZIP 不包含新孤儿。
     * 断言重点：文件选择只来自七表快照实际引用，不再扫描全部受管目录。
     */
    @Test
    void exportsStableReadOnlySnapshotWithoutFilesCreatedAfterActivityCheck() throws Exception {
        UUID lateTaskId = UUID.randomUUID();
        Path orphan = temporaryRoot.resolve("storage/uploads/" + lateTaskId + "/orphan.md");
        List<String> taskRows = new CopyOnWriteArrayList<>();
        DemoStateRepository repository = mock(DemoStateRepository.class);
        org.mockito.Mockito.doAnswer(invocation -> {
                    SnapshotTable table = invocation.getArgument(0);
                    DemoStateRepository.JsonRowConsumer consumer = invocation.getArgument(1);
                    if (table == SnapshotTable.VERIFICATION_TASK) {
                        List<String> rowsVisibleToCurrentSelect = List.copyOf(taskRows);
                        Files.createDirectories(orphan.getParent());
                        Files.writeString(orphan, "late", StandardCharsets.UTF_8);
                        taskRows.add("{\"id\":\"" + lateTaskId + "\",\"upload_path\":\""
                                + orphan.toAbsolutePath().normalize().toString().replace("\\", "\\\\") + "\"}");
                        for (String row : rowsVisibleToCurrentSelect) {
                            consumer.accept(row);
                        }
                    }
                    return null;
                })
                .when(repository)
                .exportRows(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        DemoStateService stateService = mock(DemoStateService.class);
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        SnapshotArchiveService archive = archive(repository, stateService, transactions, new DemoOperationCoordinator());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        archive.exportTo(output);

        Map<String, byte[]> entries = readZip(output.toByteArray());
        assertThat(entries.keySet()).noneMatch(name -> name.contains("orphan.md"));
        assertThat(entries.get("tables/verification_task.jsonl")).isEmpty();
        assertThat(taskRows).hasSize(1);
        assertThat(transactions.definitions())
                .anySatisfy(definition -> {
                    assertThat(definition.getIsolationLevel())
                            .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    assertThat(definition.isReadOnly()).isTrue();
                });
    }

    /**
     * 测试场景：数据库或三个受管目录不为空时请求导入。
     * 前置条件：Task 4 状态服务的 requireBlank 以统一业务异常拒绝。
     * 期望结果：归档读取和数据库插入均不发生。
     * 断言重点：快照导入复用既有空白状态边界，不提供覆盖模式。
     */
    @Test
    void rejectsImportWhenBusinessStateOrManagedStorageIsNotBlank() {
        DemoStateRepository repository = mock(DemoStateRepository.class);
        DemoStateService stateService = mock(DemoStateService.class);
        org.mockito.Mockito.doThrow(new ServiceException("DEMO_STATE_NOT_BLANK", "当前比赛数据或运行目录不为空"))
                .when(stateService)
                .requireBlank();
        SnapshotArchiveService archive = archive(repository, stateService);

        assertThatThrownBy(() -> archive.importFrom(new ByteArrayInputStream(new byte[0]), "导入快照"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("DEMO_STATE_NOT_BLANK");

        verify(repository, never())
                .insertRow(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * 测试场景：仍有活动核验或评测时请求导出。
     * 前置条件：状态服务的只读稳定性保护返回活动工作异常。
     * 期望结果：ZIP 不写入任何字节，仓储也不读取表行。
     * 断言重点：活动状态检查必须先于数据库和附件导出。
     */
    @Test
    void rejectsExportWhileVerificationOrEvaluationIsActive() throws Exception {
        DemoStateRepository repository = mock(DemoStateRepository.class);
        DemoStateService stateService = mock(DemoStateService.class);
        org.mockito.Mockito.doThrow(new ServiceException("DEMO_SNAPSHOT_ACTIVE_WORK", "仍有运行中的核验或评测"))
                .when(stateService)
                .requireQuiescentForSnapshotExport();
        SnapshotArchiveService archive = archive(repository, stateService);

        assertThatThrownBy(() -> archive.exportTo(new ByteArrayOutputStream()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("仍有运行中的核验或评测");

        verify(repository, never()).exportRows(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * 测试场景：项目根和 storageRoot 根存在配置文件与典型凭据变量文本。
     * 前置条件：三个受管目录为空，固定七表也没有业务行。
     * 期望结果：导出 ZIP 只含七表 JSONL 与 manifest，不含配置文件和凭据文本。
     * 断言重点：快照范围固定为受管目录，不能递归打包项目或开发机配置。
     */
    @Test
    void exportsNeitherConfigurationFilesNorCredentialNames() throws Exception {
        Files.createDirectories(temporaryRoot.resolve("storage"));
        Files.writeString(
                temporaryRoot.resolve("storage/application.yml"),
                "APP_DB_PASSWORD ES_PASSWORD LOCAL_MODEL_API_KEY",
                StandardCharsets.UTF_8);
        DemoStateRepository repository = emptyRepository();
        SnapshotArchiveService archive = archive(repository, mock(DemoStateService.class));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        archive.exportTo(output);

        Map<String, byte[]> entries = readZip(output.toByteArray());
        assertThat(entries.keySet()).contains("manifest.json");
        assertThat(entries.keySet()).allMatch(path -> path.equals("manifest.json") || path.startsWith("tables/"));
        String allPayloads = entries.values().stream()
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .reduce("", String::concat);
        assertThat(allPayloads)
                .doesNotContain("APP_DB_PASSWORD")
                .doesNotContain("ES_PASSWORD")
                .doesNotContain("LOCAL_MODEL_API_KEY");
    }

    /** 创建使用真实上限的服务；测试根同时充当 storageRoot，避免触碰仓库内运行数据。 */
    private SnapshotArchiveService archive(DemoStateRepository repository, DemoStateService stateService) {
        return archive(repository, stateService, 200L * 1024 * 1024, 2_000, 500L * 1024 * 1024);
    }

    /** 创建可缩小容量上限的服务，用小数据验证生产相同的计数分支。 */
    private SnapshotArchiveService archive(
            DemoStateRepository repository,
            DemoStateService stateService,
            long maxArchiveBytes,
            int maxEntryCount,
            long maxExpandedBytes) {
        return archive(
                repository,
                stateService,
                maxArchiveBytes,
                maxEntryCount,
                maxExpandedBytes,
                new RecordingTransactionManager(),
                new DemoOperationCoordinator());
    }

    /** 为快照事务与共享互斥边界断言显式注入可观测替身。 */
    private SnapshotArchiveService archive(
            DemoStateRepository repository,
            DemoStateService stateService,
            RecordingTransactionManager transactions,
            DemoOperationCoordinator coordinator) {
        return archive(
                repository,
                stateService,
                200L * 1024 * 1024,
                2_000,
                500L * 1024 * 1024,
                transactions,
                coordinator);
    }

    /** 统一构造可配置的测试快照服务，不连接真实数据库。 */
    private SnapshotArchiveService archive(
            DemoStateRepository repository,
            DemoStateService stateService,
            long maxArchiveBytes,
            int maxEntryCount,
            long maxExpandedBytes,
            RecordingTransactionManager transactions,
            DemoOperationCoordinator coordinator) {
        Path storageRoot = temporaryRoot.resolve("storage");
        WorkbenchProperties properties = new WorkbenchProperties(
                new WorkbenchProperties.DatabaseBoundary("demo", "test", false),
                storageRoot,
                temporaryRoot.resolve("manifest.json"),
                temporaryRoot.resolve("skill-source"),
                new WorkbenchProperties.Model("", "", "", ""),
                URI.create("http://127.0.0.1"));
        DemoAdminProperties adminProperties = new DemoAdminProperties(
                true, temporaryRoot.resolve("demo-materials"), maxArchiveBytes, maxEntryCount, maxExpandedBytes);
        return new SnapshotArchiveService(
                repository,
                stateService,
                properties,
                adminProperties,
                OBJECT_MAPPER,
                transactions,
                coordinator);
    }

    /** 构造一个没有表行的仓储替身，让导出聚焦文件范围而非 JDBC。 */
    private static DemoStateRepository emptyRepository() throws Exception {
        DemoStateRepository repository = mock(DemoStateRepository.class);
        org.mockito.Mockito.doAnswer(invocation -> null)
                .when(repository)
                .exportRows(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        return repository;
    }

    /** 构造七张空表的合法 v1 归档，供单点破坏测试复用。 */
    private static Map<String, byte[]> validArchiveEntries(String formatVersion) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        Map<String, SnapshotManifest.TableEntry> tables = new LinkedHashMap<>();
        for (SnapshotTable table : SnapshotTable.values()) {
            byte[] content = new byte[0];
            entries.put("tables/" + table.tableName() + ".jsonl", content);
            tables.put(table.tableName(), new SnapshotManifest.TableEntry(0, sha256(content)));
        }
        SnapshotManifest manifest =
                new SnapshotManifest(formatVersion, Instant.parse("2026-08-13T12:00:00Z"), tables, List.of());
        entries.put("manifest.json", OBJECT_MAPPER.writeValueAsBytes(manifest));
        return entries;
    }

    /** 把路径到字节映射写成真实 ZIP，保持插入顺序以便验证 entry 计数。 */
    private Path writeZip(Map<String, byte[]> entries) throws Exception {
        return writeZip(entries.entrySet().stream()
                .map(entry -> new ArchiveEntry(entry.getKey(), entry.getValue()))
                .toList());
    }

    /** 把允许规范化重名的 entry 列表写成真实 ZIP。 */
    private Path writeZip(List<ArchiveEntry> entries) throws Exception {
        Path zip = Files.createTempFile(temporaryRoot, "snapshot-", ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (ArchiveEntry entry : entries) {
                output.putNextEntry(new ZipEntry(entry.path()));
                output.write(entry.content());
                output.closeEntry();
            }
        }
        return zip;
    }

    /** 读取导出结果的全部 entry，测试数据规模很小，不影响生产流式实现约束。 */
    private static Map<String, byte[]> readZip(byte[] archive) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entries.put(entry.getName(), input.readAllBytes());
            }
        }
        return entries;
    }

    /** 计算与 manifest 相同的小写 SHA-256 十六进制摘要。 */
    private static String sha256(byte[] content) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /** 测试 ZIP 中一个允许重复规范化目标的原始 entry。 */
    private record ArchiveEntry(String path, byte[] content) {}

    /** 为 TransactionTemplate 提供不连接数据库的同步提交/回滚边界。 */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final List<TransactionDefinition> definitions = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            definitions.add(new org.springframework.transaction.support.DefaultTransactionDefinition(definition));
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}

        private List<TransactionDefinition> definitions() {
            return List.copyOf(definitions);
        }
    }
}
