package com.hsmap.factverification.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.shared.ServiceException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 被测试对象：{@link SnapshotArchiveService} 的物理存储根、ZIP 类型和 JSONL 字节合同。
 * 测试目的：证明导入导出在任何写入前拒绝不可信 storageRoot，并严格拒绝特殊 Unix 类型与非法 UTF-8。
 * 覆盖范围：祖先 symlink/非目录、FIFO/socket/设备条目和有界 JSONL 解码。
 * 前置条件：所有文件都位于 JUnit 临时目录；仓储、状态与事务均为替身，不调用真实管理 API 或共享数据库。
 */
class SnapshotArchiveStrictContractTest {

    @TempDir
    Path temporaryRoot;

    /**
     * 测试场景：导入使用的 storageRoot 位于一个符号链接祖先之下。
     * 前置条件：链接目标已有不可修改的 marker，配置根本身尚不存在。
     * 期望结果：导入在创建 .demo-import 或 storageRoot 前失败。
     * 断言重点：链接目标 marker 字节不变，且根外不能出现配置根目录。
     */
    @Test
    void rejectsImportBeforeFollowingStorageRootAncestorSymlink() throws Exception {
        PhysicalRootFixture fixture = symlinkAncestorFixture("import-marker");
        Path archiveFile = temporaryRoot.resolve("untrusted.zip");
        Files.write(archiveFile, new byte[] {1, 2, 3});

        assertThatThrownBy(() -> service(fixture.configuredRoot()).validateAndStage(archiveFile))
                .isInstanceOf(ServiceException.class);

        assertPhysicalTargetUntouched(fixture);
    }

    /**
     * 测试场景：导出使用的 storageRoot 位于一个符号链接祖先之下。
     * 前置条件：链接目标已有不可修改的 marker，HTTP 输出只使用内存流。
     * 期望结果：导出在创建 .demo-export 或 ZIP 文件前失败。
     * 断言重点：导入与导出必须共享相同 NOFOLLOW 物理根门禁。
     */
    @Test
    void rejectsExportBeforeFollowingStorageRootAncestorSymlink() throws Exception {
        PhysicalRootFixture fixture = symlinkAncestorFixture("export-marker");

        assertThatThrownBy(() -> service(fixture.configuredRoot()).exportTo(new ByteArrayOutputStream()))
                .isInstanceOf(ServiceException.class);

        assertPhysicalTargetUntouched(fixture);
    }

    /**
     * 测试场景：storageRoot 的已存在祖先是普通文件而不是目录。
     * 前置条件：该普通文件本身承载 marker 原字节，候选根位于其下。
     * 期望结果：导入和导出均失败，且不得截断或替换该文件。
     * 断言重点：非目录祖先与符号链接祖先都在创建任何目录前失败关闭。
     */
    @Test
    void rejectsNonDirectoryStorageRootAncestorWithoutChangingMarker() throws Exception {
        Path marker = temporaryRoot.resolve("ancestor-file");
        byte[] original = "non-directory-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(marker, original);
        SnapshotArchiveService archive = service(marker.resolve("storage"));
        Path zip = temporaryRoot.resolve("invalid.zip");
        Files.write(zip, new byte[] {1});

        assertThatThrownBy(() -> archive.validateAndStage(zip)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> archive.exportTo(new ByteArrayOutputStream())).isInstanceOf(ServiceException.class);

        assertThat(Files.readAllBytes(marker)).isEqualTo(original);
    }

    /**
     * 测试场景：ZIP 中声明 FIFO、socket、字符设备或块设备 Unix 类型。
     * 前置条件：Commons Compress 真正把 mode 写入中央目录 external attributes。
     * 期望结果：所有特殊类型都在解压落盘前以固定错误码拒绝。
     * 断言重点：不能只拒绝 symlink，也不能把特殊节点当成普通零字节文件。
     */
    @ParameterizedTest
    @ValueSource(ints = {4096, 8192, 24576, 49152})
    void rejectsSpecialUnixFileTypes(int unixType) throws Exception {
        Path zip = temporaryRoot.resolve("special-" + unixType + ".zip");
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(zip)) {
            ZipArchiveEntry entry = new ZipArchiveEntry("files/uploads/task/special");
            entry.setUnixMode(unixType | UnixStat.DEFAULT_FILE_PERM);
            output.putArchiveEntry(entry);
            output.closeArchiveEntry();
        }

        assertThatThrownBy(() -> service(temporaryRoot.resolve("storage-" + unixType)).validateAndStage(zip))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("DEMO_SNAPSHOT_SPECIAL_ENTRY"));
    }

    /**
     * 测试场景：表 JSONL 单行含不能按 UTF-8 解码的孤立字节。
     * 前置条件：字节数低于单行上限，排除容量错误干扰。
     * 期望结果：解码以 REPORT 策略失败并返回不泄漏原字节的稳定业务错误。
     * 断言重点：不能由 String 构造器静默替换为 U+FFFD 后继续导入数据库。
     */
    @Test
    void rejectsMalformedUtf8JsonLine() throws Exception {
        Path jsonl = temporaryRoot.resolve("invalid-utf8.jsonl");
        Files.write(jsonl, new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xC3, '"', '}', '\n'});

        assertThatThrownBy(() -> SnapshotArchiveService.readUtf8Lines(jsonl, 1024, ignored -> {}))
                .isInstanceOfSatisfying(ServiceException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("DEMO_SNAPSHOT_JSONL_UTF8_INVALID");
                    assertThat(exception.getMessage()).doesNotContain("C3", "�");
                });
    }

    /** 创建祖先链接与根外 marker；configuredRoot 的词法路径仍位于链接名下。 */
    private PhysicalRootFixture symlinkAncestorFixture(String markerText) throws Exception {
        Path physicalParent = temporaryRoot.resolve(markerText + "-physical");
        Files.createDirectory(physicalParent);
        Path marker = physicalParent.resolve("marker.bin");
        byte[] markerBytes = markerText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(marker, markerBytes);
        Path linkedParent = temporaryRoot.resolve(markerText + "-link");
        Files.createSymbolicLink(linkedParent, physicalParent);
        return new PhysicalRootFixture(linkedParent.resolve("storage"), physicalParent, marker, markerBytes);
    }

    /** 同时核对 marker 内容和根外目录集合，证明失败路径没有产生暂存副作用。 */
    private static void assertPhysicalTargetUntouched(PhysicalRootFixture fixture) throws Exception {
        assertThat(Files.readAllBytes(fixture.marker())).isEqualTo(fixture.markerBytes());
        try (var entries = Files.list(fixture.physicalParent())) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList()).containsExactly("marker.bin");
        }
    }

    /** 构造只会进入文件边界的快照服务，不提供任何可访问共享数据库的真实依赖。 */
    private static SnapshotArchiveService service(Path storageRoot) {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        DemoStateRepository repository = mock(DemoStateRepository.class);
        DemoStateService stateService = mock(DemoStateService.class);
        org.mockito.Mockito.when(stateService.status()).thenReturn(new DemoStateView(Map.of(), Map.of()));
        WorkbenchProperties workbench = new WorkbenchProperties(
                new WorkbenchProperties.DatabaseBoundary("demo", "test", false),
                storageRoot,
                Path.of("evals/manifest.json"),
                Path.of("skills/company-material-fact-check"),
                new WorkbenchProperties.Model("", "", "", ""),
                URI.create("http://127.0.0.1:19091/mcp"));
        DemoAdminProperties admin = new DemoAdminProperties(
                true,
                Path.of("evals/demo-materials"),
                Path.of("skills/presets"),
                209_715_200L,
                2_000,
                524_288_000L);
        return new SnapshotArchiveService(
                repository,
                stateService,
                workbench,
                admin,
                objectMapper,
                mock(PlatformTransactionManager.class));
    }

    /** 记录链接测试的词法配置根与应保持原样的物理目标。 */
    private record PhysicalRootFixture(
            Path configuredRoot, Path physicalParent, Path marker, byte[] markerBytes) {}
}
