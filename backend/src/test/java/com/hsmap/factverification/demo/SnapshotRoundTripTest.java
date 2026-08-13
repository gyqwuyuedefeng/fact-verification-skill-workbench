package com.hsmap.factverification.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsmap.factverification.config.WorkbenchProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * 被测试对象：SnapshotArchiveService 的七表与三个受管目录端到端往返。
 * 测试目的：证明 UUID、时间、JSONB 和业务字段原值恢复，仅 upload_path 随目标 storageRoot 归一化。
 * 覆盖范围：固定导入顺序、Skill 循环引用二阶段恢复、文件摘要一致性以及数据库异常后的空白补偿。
 * 前置条件：内存仓储替身模拟应用仓储写入，TransactionTemplate 使用本地记录型管理器，不连接数据库客户端。
 */
class SnapshotRoundTripTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path temporaryRoot;

    /**
     * 测试场景：一份包含七表业务行、原始附件和 Skill 文件的快照从开发根导入到另一 storageRoot。
     * 前置条件：Skill 子版本同时引用父版本与注册评测，任务 upload_path 是源环境绝对路径。
     * 期望结果：七表行数及字段往返一致，唯一变化是 upload_path 指向目标环境规范路径，文件 SHA-256 不变。
     * 断言重点：Skill 引用在 evaluation_run 导入后恢复，JSONB/UUID/时间未被字符串化或重新生成。
     */
    @Test
    void roundTripsSevenTablesAndManagedFilesWithoutChangingBusinessValues() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID parentSkillId = UUID.randomUUID();
        UUID childSkillId = UUID.randomUUID();
        UUID evaluationId = UUID.randomUUID();
        Path sourceStorage = temporaryRoot.resolve("source-storage");
        Path targetStorage = temporaryRoot.resolve("target-storage");
        byte[] material = "原始材料字节".getBytes(StandardCharsets.UTF_8);
        byte[] frozenSkill = "# 冻结 Skill\n保持原字节".getBytes(StandardCharsets.UTF_8);
        byte[] runtimeSkill = "# 运行 Skill\n保持原字节".getBytes(StandardCharsets.UTF_8);
        write(sourceStorage.resolve("uploads/" + taskId + "/material.md"), material);
        write(sourceStorage.resolve("skill-snapshots/" + childSkillId + "/skill.md"), frozenSkill);
        write(sourceStorage.resolve("skill-runtime/" + childSkillId + "/SKILL.md"), runtimeSkill);

        EnumMap<SnapshotTable, List<String>> sourceRows = sampleRows(
                taskId,
                parentSkillId,
                childSkillId,
                evaluationId,
                sourceStorage
                        .resolve("uploads/" + taskId + "/material.md")
                        .toAbsolutePath()
                        .normalize());
        InMemorySnapshotRepository sourceRepository = new InMemorySnapshotRepository(sourceRows);
        SnapshotArchiveService exporter = archive(sourceRepository, sourceStorage);
        ByteArrayOutputStream zip = new ByteArrayOutputStream();

        exporter.exportTo(zip);

        InMemorySnapshotRepository targetRepository =
                new InMemorySnapshotRepository(new EnumMap<>(SnapshotTable.class));
        SnapshotArchiveService importer = archive(targetRepository, targetStorage);
        importer.importFrom(new ByteArrayInputStream(zip.toByteArray()), "导入快照");

        assertThat(targetRepository.rows())
                .allSatisfy((table, rows) ->
                        assertThat(rows).as("表 %s 行数", table.tableName()).hasSameSizeAs(sourceRows.get(table)));
        assertRowsEqualExceptUploadPath(sourceRows, targetRepository.rows(), taskId, targetStorage);
        assertThat(sha256(targetStorage.resolve("uploads/" + taskId + "/material.md")))
                .isEqualTo(sha256(material));
        assertThat(sha256(targetStorage.resolve("skill-snapshots/" + childSkillId + "/skill.md")))
                .isEqualTo(sha256(frozenSkill));
        assertThat(sha256(targetStorage.resolve("skill-runtime/" + childSkillId + "/SKILL.md")))
                .isEqualTo(sha256(runtimeSkill));
        assertThat(targetRepository.importOrder())
                .containsSubsequence(
                        SnapshotTable.SKILL_VERSION,
                        SnapshotTable.EVALUATION_RUN,
                        SnapshotTable.VERIFICATION_TASK,
                        SnapshotTable.VERIFICATION_RUN,
                        SnapshotTable.CLAIM,
                        SnapshotTable.EVIDENCE_SNAPSHOT,
                        SnapshotTable.RELEASE_BINDING);
        assertThat(targetRepository.skillReferencesRestored()).isTrue();
        assertThat(targetRepository.lockBeforeFirstInsert()).isTrue();
    }

    /**
     * 测试场景：数据库导入到 claim 时发生运行时异常。
     * 前置条件：归档已完整校验，目标数据库和三个正式目录最初为空。
     * 期望结果：事务管理器回滚此前插入行，服务不再发起二次 clearAll 补偿。
     * 断言重点：数据库失败仅依赖本次事务回滚，不能删除其他请求的状态。
     */
    @Test
    void restoresBlankStateWhenDatabaseImportFails() throws Exception {
        UUID taskId = UUID.randomUUID();
        Path sourceStorage = temporaryRoot.resolve("rollback-source");
        Path targetStorage = temporaryRoot.resolve("rollback-target");
        UUID childSkillId = UUID.randomUUID();
        write(sourceStorage.resolve("uploads/" + taskId + "/material.md"), "material".getBytes(StandardCharsets.UTF_8));
        writeFrozenSkillDirectories(sourceStorage, childSkillId);
        EnumMap<SnapshotTable, List<String>> sourceRows = sampleRows(
                taskId,
                UUID.randomUUID(),
                childSkillId,
                UUID.randomUUID(),
                sourceStorage
                        .resolve("uploads/" + taskId + "/material.md")
                        .toAbsolutePath()
                        .normalize());
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        archive(new InMemorySnapshotRepository(sourceRows), sourceStorage).exportTo(zip);
        InMemorySnapshotRepository failingRepository =
                new InMemorySnapshotRepository(new EnumMap<>(SnapshotTable.class));
        failingRepository.failAt(SnapshotTable.CLAIM);

        assertThatThrownBy(() -> archive(failingRepository, targetStorage)
                        .importFrom(new ByteArrayInputStream(zip.toByteArray()), "导入快照"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模拟数据库故障");

        assertThat(failingRepository.rows().values()).allMatch(List::isEmpty);
        assertThat(failingRepository.clearAllCalls()).isZero();
        assertManagedDirectoriesBlank(targetStorage);
    }

    /**
     * 测试场景：数据库行已写入后，第二个正式目录在原子交换前意外变为非空。
     * 前置条件：仓储替身在最后一表插入时模拟并发运行产物，迫使文件交换中途失败。
     * 期望结果：事务回滚导入行，并恢复 prepare 前三个目录均缺失的精确形态。
     * 断言重点：普通文件生产者受共享读锁保护，写锁内测试替身注入的异常文件属于本次破坏性操作现场，恢复时不得遗留。
     */
    @Test
    void restoresBlankStateWhenManagedDirectorySwapFails() throws Exception {
        UUID taskId = UUID.randomUUID();
        Path sourceStorage = temporaryRoot.resolve("swap-source");
        Path targetStorage = temporaryRoot.resolve("swap-target");
        UUID childSkillId = UUID.randomUUID();
        write(sourceStorage.resolve("uploads/" + taskId + "/material.md"), "material".getBytes(StandardCharsets.UTF_8));
        writeFrozenSkillDirectories(sourceStorage, childSkillId);
        EnumMap<SnapshotTable, List<String>> sourceRows = sampleRows(
                taskId,
                UUID.randomUUID(),
                childSkillId,
                UUID.randomUUID(),
                sourceStorage
                        .resolve("uploads/" + taskId + "/material.md")
                        .toAbsolutePath()
                        .normalize());
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        archive(new InMemorySnapshotRepository(sourceRows), sourceStorage).exportTo(zip);
        InMemorySnapshotRepository targetRepository =
                new InMemorySnapshotRepository(new EnumMap<>(SnapshotTable.class));
        targetRepository.afterReleaseInsert(() -> {
            try {
                write(
                        targetStorage.resolve("skill-snapshots/concurrent.txt"),
                        "block".getBytes(StandardCharsets.UTF_8));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });

        assertThatThrownBy(() -> archive(targetRepository, targetStorage)
                        .importFrom(new ByteArrayInputStream(zip.toByteArray()), "导入快照"))
                .isInstanceOf(com.hsmap.factverification.shared.ServiceException.class)
                .hasMessageContaining("正式运行目录在导入期间变为非空");

        assertThat(targetRepository.rows().values()).allMatch(List::isEmpty);
        assertThat(targetStorage.resolve("uploads")).doesNotExist();
        assertThat(targetStorage.resolve("skill-snapshots/concurrent.txt")).doesNotExist();
        assertThat(targetStorage.resolve("skill-runtime")).doesNotExist();
    }

    /**
     * 测试场景：一次导入在事务内暂停时，管理员同时发起 reset。
     * 前置条件：快照与 reset 显式共享同一个 DemoOperationCoordinator。
     * 期望结果：导入释放独占边界前，reset 不得进入 clearAll；之后两个操作按顺序完成。
     * 断言重点：空白检查、数据库事务与目录交换之间不存在 reset/import 交错窗口。
     */
    @Test
    void doesNotInterleaveResetWithImport() throws Exception {
        UUID taskId = UUID.randomUUID();
        Path sourceStorage = temporaryRoot.resolve("serialized-source");
        Path targetStorage = temporaryRoot.resolve("serialized-target");
        UUID childSkillId = UUID.randomUUID();
        write(sourceStorage.resolve("uploads/" + taskId + "/material.md"), "material".getBytes(StandardCharsets.UTF_8));
        writeFrozenSkillDirectories(sourceStorage, childSkillId);
        EnumMap<SnapshotTable, List<String>> sourceRows = sampleRows(
                taskId,
                UUID.randomUUID(),
                childSkillId,
                UUID.randomUUID(),
                sourceStorage.resolve("uploads/" + taskId + "/material.md").toAbsolutePath().normalize());
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        archive(new InMemorySnapshotRepository(sourceRows), sourceStorage).exportTo(zip);

        InMemorySnapshotRepository targetRepository =
                new InMemorySnapshotRepository(new EnumMap<>(SnapshotTable.class));
        CountDownLatch importEntered = new CountDownLatch(1);
        CountDownLatch allowImport = new CountDownLatch(1);
        targetRepository.pauseFirstInsert(importEntered, allowImport);
        DemoOperationCoordinator coordinator = new DemoOperationCoordinator();
        RecordingTransactionManager transactions = new RecordingTransactionManager(targetRepository::rollbackImportedRows);
        DemoStateService stateService = new DemoStateService(
                targetRepository, new ManagedStorageSwap(targetStorage), transactions, coordinator);
        SnapshotArchiveService importer = archive(targetRepository, targetStorage, stateService, transactions, coordinator);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> importing = executor.submit(() ->
                    importer.importFrom(new ByteArrayInputStream(zip.toByteArray()), "导入快照"));
            assertThat(importEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> resetting = executor.submit(() ->
                    stateService.reset("serialized-reset", "清空全部比赛数据"));

            Thread.sleep(100);
            assertThat(targetRepository.clearAllCalls()).isZero();
            allowImport.countDown();
            importing.get(5, TimeUnit.SECONDS);
            resetting.get(5, TimeUnit.SECONDS);
            assertThat(targetRepository.clearAllCalls()).isEqualTo(1);
        } finally {
            allowImport.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 测试场景：失败导入暂停时，另一请求使用同一竞赛状态边界导入合法快照。
     * 前置条件：首次仅在 claim 插入时失败，测试事务管理器模拟数据库回滚本次写入。
     * 期望结果：第二次导入在失败操作完整退出后成功，最终行与文件均保留。
     * 断言重点：失败路径不得无条件 clearAll，更不得删除另一请求的成功结果。
     */
    @Test
    void failedImportDoesNotDeleteAnotherSuccessfulImport() throws Exception {
        UUID taskId = UUID.randomUUID();
        Path sourceStorage = temporaryRoot.resolve("concurrent-source");
        Path targetStorage = temporaryRoot.resolve("concurrent-target");
        UUID childSkillId = UUID.randomUUID();
        write(sourceStorage.resolve("uploads/" + taskId + "/material.md"), "material".getBytes(StandardCharsets.UTF_8));
        writeFrozenSkillDirectories(sourceStorage, childSkillId);
        EnumMap<SnapshotTable, List<String>> sourceRows = sampleRows(
                taskId,
                UUID.randomUUID(),
                childSkillId,
                UUID.randomUUID(),
                sourceStorage.resolve("uploads/" + taskId + "/material.md").toAbsolutePath().normalize());
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        archive(new InMemorySnapshotRepository(sourceRows), sourceStorage).exportTo(zip);

        InMemorySnapshotRepository targetRepository =
                new InMemorySnapshotRepository(new EnumMap<>(SnapshotTable.class));
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch allowFirst = new CountDownLatch(1);
        targetRepository.pauseFirstInsert(firstEntered, allowFirst);
        targetRepository.failOnceAt(SnapshotTable.CLAIM);
        DemoOperationCoordinator coordinator = new DemoOperationCoordinator();
        RecordingTransactionManager transactions = new RecordingTransactionManager(targetRepository::rollbackImportedRows);
        DemoStateService stateService = mock(DemoStateService.class);
        SnapshotArchiveService importer = archive(targetRepository, targetStorage, stateService, transactions, coordinator);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> failed = executor.submit(() ->
                    importer.importFrom(new ByteArrayInputStream(zip.toByteArray()), "导入快照"));
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> successful = executor.submit(() ->
                    importer.importFrom(new ByteArrayInputStream(zip.toByteArray()), "导入快照"));
            allowFirst.countDown();

            assertThatThrownBy(() -> failed.get(5, TimeUnit.SECONDS))
                    .hasRootCauseMessage("模拟数据库故障：claim");
            successful.get(5, TimeUnit.SECONDS);
            assertThat(targetRepository.rows())
                    .allSatisfy((table, rows) -> assertThat(rows).hasSameSizeAs(sourceRows.get(table)));
            assertThat(targetRepository.clearAllCalls()).isZero();
            assertThat(targetStorage.resolve("uploads/" + taskId + "/material.md")).exists();
        } finally {
            allowFirst.countDown();
            executor.shutdownNow();
        }
    }

    /** 创建使用生产三重容量值的快照服务，状态边界由无活动/空白 Mock 表示。 */
    private SnapshotArchiveService archive(DemoStateRepository repository, Path storageRoot) {
        RecordingTransactionManager transactions = repository instanceof InMemorySnapshotRepository memory
                ? new RecordingTransactionManager(memory::rollbackImportedRows)
                : new RecordingTransactionManager();
        return archive(
                repository,
                storageRoot,
                mock(DemoStateService.class),
                transactions,
                new DemoOperationCoordinator());
    }

    /** 显式共享状态门禁、事务替身和单用途协调器，供并发边界测试使用。 */
    private SnapshotArchiveService archive(
            DemoStateRepository repository,
            Path storageRoot,
            DemoStateService stateService,
            RecordingTransactionManager transactions,
            DemoOperationCoordinator coordinator) {
        WorkbenchProperties properties = new WorkbenchProperties(
                new WorkbenchProperties.DatabaseBoundary("demo", "test", false),
                storageRoot,
                temporaryRoot.resolve("manifest.json"),
                temporaryRoot.resolve("skill-source"),
                new WorkbenchProperties.Model("", "", "", ""),
                URI.create("http://127.0.0.1"));
        DemoAdminProperties adminProperties = new DemoAdminProperties(
                true, temporaryRoot.resolve("demo-materials"), 200L * 1024 * 1024, 2_000, 500L * 1024 * 1024);
        return new SnapshotArchiveService(
                repository,
                stateService,
                properties,
                adminProperties,
                OBJECT_MAPPER,
                transactions,
                coordinator);
    }

    /** 构造包含七表行、Skill 循环引用及嵌套 JSONB 的代表性内存数据。 */
    private static EnumMap<SnapshotTable, List<String>> sampleRows(
            UUID taskId, UUID parentSkillId, UUID childSkillId, UUID evaluationId, Path uploadPath) throws Exception {
        EnumMap<SnapshotTable, List<String>> rows = new EnumMap<>(SnapshotTable.class);
        rows.put(
                SnapshotTable.VERIFICATION_TASK,
                List.of("{\"id\":\"" + taskId + "\",\"request_id\":\"task-request\",\"upload_path\":\""
                        + jsonEscape(uploadPath.toString())
                        + "\",\"document_snapshot\":{\"blocks\":[{\"text\":\"保持"
                        + " JSONB\"}]},\"created_at\":\"2026-08-13T12:00:00Z\"}"));
        rows.put(
                SnapshotTable.SKILL_VERSION,
                List.of(
                        "{\"id\":\"" + parentSkillId
                                + "\",\"status\":\"DRAFT\",\"parent_version_id\":null,\"registered_evaluation_id\":null,\"references_json\":[{\"name\":\"父版本\"}],\"created_at\":\"2026-08-13T12:01:00Z\"}",
                        "{\"id\":\"" + childSkillId + "\",\"parent_version_id\":\"" + parentSkillId
                                + "\",\"status\":\"CANDIDATE\",\"registered_evaluation_id\":\"" + evaluationId
                                + "\",\"references_json\":[{\"name\":\"子版本\"}],\"created_at\":\"2026-08-13T12:02:00Z\"}"));
        rows.put(
                SnapshotTable.EVALUATION_RUN,
                List.of(
                        "{\"id\":\"" + evaluationId
                                + "\",\"run_manifest_json\":{\"datasetHash\":\"abc\"},\"created_at\":\"2026-08-13T12:03:00Z\"}"));
        rows.put(
                SnapshotTable.VERIFICATION_RUN,
                List.of("{\"id\":\"" + UUID.randomUUID() + "\",\"task_id\":\"" + taskId
                        + "\",\"result_json\":{\"claims\":[1,2]},\"created_at\":\"2026-08-13T12:04:00Z\"}"));
        rows.put(
                SnapshotTable.CLAIM,
                List.of("{\"id\":\"" + UUID.randomUUID()
                        + "\",\"material_locator\":{\"page\":1},\"created_at\":\"2026-08-13T12:05:00Z\"}"));
        rows.put(
                SnapshotTable.EVIDENCE_SNAPSHOT,
                List.of(
                        "{\"id\":\"" + UUID.randomUUID()
                                + "\",\"canonical_arguments\":{\"companyId\":\"9133\"},\"created_at\":\"2026-08-13T12:06:00Z\"}"));
        rows.put(
                SnapshotTable.RELEASE_BINDING,
                List.of("{\"id\":\"" + UUID.randomUUID() + "\",\"stable_version_id\":\"" + parentSkillId
                        + "\",\"state_after\":{\"stable\":true},\"created_at\":\"2026-08-13T12:07:00Z\"}"));
        return rows;
    }

    /** 对比所有 JSON 行，仅把任务上传路径替换成目标环境绝对规范路径后再断言。 */
    private static void assertRowsEqualExceptUploadPath(
            Map<SnapshotTable, List<String>> source,
            Map<SnapshotTable, List<String>> imported,
            UUID taskId,
            Path targetStorage)
            throws Exception {
        for (SnapshotTable table : SnapshotTable.values()) {
            List<JsonNode> expected = new ArrayList<>();
            for (String row : source.get(table)) {
                ObjectNode node = (ObjectNode) OBJECT_MAPPER.readTree(row);
                if (table == SnapshotTable.VERIFICATION_TASK) {
                    node.put(
                            "upload_path",
                            targetStorage
                                    .resolve("uploads/" + taskId + "/material.md")
                                    .toAbsolutePath()
                                    .normalize()
                                    .toString());
                }
                expected.add(node);
            }
            List<JsonNode> actual = imported.get(table).stream()
                    .map(value -> {
                        try {
                            return OBJECT_MAPPER.readTree(value);
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .toList();
            assertThat(actual).as("表 %s 内容", table.tableName()).containsExactlyElementsOf(expected);
        }
    }

    /** 创建父目录后写测试文件，模拟三个受管目录的真实运行产物。 */
    private static void write(Path path, byte[] content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, content);
    }

    /** 为非 DRAFT 样例同时建立 snapshot/runtime 双目录，符合可恢复快照的冻结文件不变量。 */
    private static void writeFrozenSkillDirectories(Path storageRoot, UUID versionId) throws Exception {
        write(storageRoot.resolve("skill-snapshots/" + versionId + "/skill.md"), "snapshot".getBytes(StandardCharsets.UTF_8));
        write(storageRoot.resolve("skill-runtime/" + versionId + "/SKILL.md"), "runtime".getBytes(StandardCharsets.UTF_8));
    }

    /** 断言失败补偿后三个目录没有业务文件，只允许保留 .gitkeep。 */
    private static void assertManagedDirectoriesBlank(Path storageRoot) throws Exception {
        for (String directory : List.of("uploads", "skill-snapshots", "skill-runtime")) {
            Path root = storageRoot.resolve(directory);
            if (Files.notExists(root)) {
                continue;
            }
            assertThat(root).isDirectory();
            try (var paths = Files.walk(root)) {
                assertThat(paths.filter(path -> !path.equals(root))
                                .map(path -> path.getFileName().toString())
                                .toList())
                        .containsExactly(".gitkeep");
            }
        }
    }

    /** 计算磁盘文件摘要。 */
    private static String sha256(Path path) throws Exception {
        return sha256(Files.readAllBytes(path));
    }

    /** 计算内存字节摘要。 */
    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** 生成可嵌入 JSON 字符串的跨平台路径文本。 */
    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 以 EnumMap 保存表行的应用仓储替身；它模拟数据库复合行 JSON 导出、单行插入及 Skill 引用回填。
     * 所有写入只发生在测试内存中，符合数据库客户端只读约束。
     */
    private static final class InMemorySnapshotRepository extends DemoStateRepository {
        private final EnumMap<SnapshotTable, List<String>> rows = new EnumMap<>(SnapshotTable.class);
        private final List<SnapshotTable> importOrder = new ArrayList<>();
        private SnapshotTable failureTable;
        private boolean failOnlyOnce;
        private boolean skillReferencesRestored;
        private Runnable afterReleaseInsert;
        private CountDownLatch firstInsertEntered;
        private CountDownLatch allowFirstInsert;
        private boolean firstInsertPaused;
        private int clearAllCalls;
        private boolean tableLockAcquired;
        private boolean lockBeforeFirstInsert = true;

        private InMemorySnapshotRepository(Map<SnapshotTable, List<String>> initialRows) {
            super(mock(JdbcTemplate.class));
            for (SnapshotTable table : SnapshotTable.values()) {
                rows.put(table, new ArrayList<>(initialRows.getOrDefault(table, List.of())));
            }
        }

        @Override
        public void exportRows(SnapshotTable table, JsonRowConsumer consumer) throws java.io.IOException {
            for (String row : rows.get(table)) {
                consumer.accept(row);
            }
        }

        @Override
        public void insertRow(SnapshotTable table, String json) {
            lockBeforeFirstInsert &= tableLockAcquired;
            if (!firstInsertPaused && firstInsertEntered != null) {
                firstInsertPaused = true;
                firstInsertEntered.countDown();
                try {
                    if (!allowFirstInsert.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("模拟导入等待超时");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("模拟导入被中断", exception);
                }
            }
            if (table == failureTable) {
                if (failOnlyOnce) {
                    failureTable = null;
                }
                throw new IllegalStateException("模拟数据库故障：" + table.tableName());
            }
            rows.get(table).add(json);
            importOrder.add(table);
            if (table == SnapshotTable.RELEASE_BINDING && afterReleaseInsert != null) {
                afterReleaseInsert.run();
            }
        }

        @Override
        public void lockAllTablesForStateReplacement() {
            tableLockAcquired = true;
        }

        @Override
        public void restoreSkillReferences(UUID id, UUID parentVersionId, UUID registeredEvaluationId) {
            List<String> skillRows = rows.get(SnapshotTable.SKILL_VERSION);
            for (int index = 0; index < skillRows.size(); index++) {
                try {
                    ObjectNode node = (ObjectNode) OBJECT_MAPPER.readTree(skillRows.get(index));
                    if (id.toString().equals(node.path("id").asText())) {
                        if (parentVersionId == null) {
                            node.putNull("parent_version_id");
                        } else {
                            node.put("parent_version_id", parentVersionId.toString());
                        }
                        if (registeredEvaluationId == null) {
                            node.putNull("registered_evaluation_id");
                        } else {
                            node.put("registered_evaluation_id", registeredEvaluationId.toString());
                        }
                        skillRows.set(index, OBJECT_MAPPER.writeValueAsString(node));
                    }
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
            skillReferencesRestored = true;
        }

        @Override
        public void clearAll() {
            clearAllCalls++;
            rows.values().forEach(List::clear);
        }

        @Override
        public Map<String, Long> counts() {
            Map<String, Long> counts = new java.util.LinkedHashMap<>();
            for (SnapshotTable table : SnapshotTable.values()) {
                counts.put(table.tableName(), (long) rows.get(table).size());
            }
            return Map.copyOf(counts);
        }

        private Map<SnapshotTable, List<String>> rows() {
            return rows;
        }

        private List<SnapshotTable> importOrder() {
            return importOrder;
        }

        private boolean skillReferencesRestored() {
            return skillReferencesRestored;
        }

        private void failAt(SnapshotTable table) {
            failureTable = table;
        }

        /** 只让第一个进入目标表的导入失败，后续串行请求可继续。 */
        private void failOnceAt(SnapshotTable table) {
            failureTable = table;
            failOnlyOnce = true;
        }

        /** 暂停首次插入，让测试能精确观察另一管理操作是否越过独占边界。 */
        private void pauseFirstInsert(CountDownLatch entered, CountDownLatch allow) {
            firstInsertEntered = entered;
            allowFirstInsert = allow;
        }

        /** 模拟真实数据库事务回滚，不经过生产 clearAll 补偿通道。 */
        private void rollbackImportedRows() {
            rows.values().forEach(List::clear);
            importOrder.clear();
            skillReferencesRestored = false;
        }

        private int clearAllCalls() {
            return clearAllCalls;
        }

        private boolean lockBeforeFirstInsert() {
            return lockBeforeFirstInsert;
        }

        /** 在最后一张表插入后、文件交换前触发可控测试动作。 */
        private void afterReleaseInsert(Runnable action) {
            afterReleaseInsert = action;
        }
    }

    /** 为 TransactionTemplate 提供无需数据库连接的本地事务生命周期。 */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final Runnable rollbackAction;

        private RecordingTransactionManager() {
            this(() -> {});
        }

        private RecordingTransactionManager(Runnable rollbackAction) {
            this.rollbackAction = rollbackAction;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbackAction.run();
        }
    }
}
