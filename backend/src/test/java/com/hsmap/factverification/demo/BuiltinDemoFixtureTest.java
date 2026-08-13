package com.hsmap.factverification.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.agent.FrozenSkillLoader;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.shared.ServiceException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 被测试对象：{@link BuiltinDemoFixtureService} 与脱敏的 builtin-demo.json 声明。
 * 测试目的：证明固定 UUID 的完整演示状态通过 Task 5 同一快照导入路径恢复，且资源复制、哈希和空状态门禁失败关闭。
 * 覆盖范围：四种 Skill 状态、三次评测、两条 PRIMARY/SHADOW、人工 PASS/FAIL、五步发布历史、附件与冻结目录。
 * 前置条件：SnapshotArchiveService 使用 Mock 捕获其接收的 v1 ZIP；不连接数据库、不调用模型或 MCP，不使用客户端写库。
 */
class BuiltinDemoFixtureTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Resource FIXTURE = new ClassPathResource("demo-state/builtin-demo.json");
    private static final Path PRESET_ROOT = Path.of("../skills/presets");
    private static final Path MATERIAL_ROOT = Path.of("../evals/demo-materials");
    private static final List<String> RELEASE_ACTIONS =
            List.of("INITIALIZE", "REGISTER", "SHADOW_START", "PROMOTE", "ROLLBACK");

    @TempDir
    Path temporaryRoot;

    /**
     * 测试场景：读取声明式 fixture 并检查最终可查询的七表关系数据。
     * 前置条件：fixture 使用固定前缀 UUID，表内容不依赖当前数据库、模型结果或 MCP 响应。
     * 期望结果：状态、评测变体、影子复核和发布 revision 严格符合三阶段演示序列。
     * 断言重点：发布历史必须存在于 release_binding 数组，不能只是页面说明文字。
     */
    @Test
    void declaresFixedRelationalDemoStateWithoutExternalRuntimeDependencies() throws Exception {
        JsonNode fixture = OBJECT_MAPPER.readTree(FIXTURE.getInputStream());
        JsonNode tables = fixture.path("tables");

        assertThat(values(tables.path("skill_version"), "id"))
                .containsExactly(
                        "10000000-0000-0000-0000-000000000001",
                        "10000000-0000-0000-0000-000000000002",
                        "10000000-0000-0000-0000-000000000003",
                        "10000000-0000-0000-0000-000000000004");
        assertThat(values(tables.path("skill_version"), "status"))
                .containsExactlyInAnyOrder("STABLE", "ARCHIVED", "CANDIDATE", "DRAFT");
        assertThat(values(tables.path("evaluation_run"), "gate_status"))
                .containsExactlyInAnyOrder("PASS", "PASS", "FAIL");
        assertThat(variantIdentifiers(tables.path("evaluation_run").get(0)))
                .containsExactly("BASELINE", "10000000-0000-0000-0000-000000000001");
        assertThat(variantIdentifiers(tables.path("evaluation_run").get(1)))
                .containsExactly(
                        "BASELINE",
                        "10000000-0000-0000-0000-000000000001",
                        "10000000-0000-0000-0000-000000000002");
        assertThat(variantIdentifiers(tables.path("evaluation_run").get(2)))
                .containsExactly(
                        "BASELINE",
                        "10000000-0000-0000-0000-000000000001",
                        "10000000-0000-0000-0000-000000000003");
        assertThat(tables.path("verification_task")).hasSize(2);
        assertThat(values(tables.path("verification_run"), "run_type"))
                .containsExactlyInAnyOrder("PRIMARY", "SHADOW", "PRIMARY", "SHADOW");
        assertThat(values(tables.path("verification_run"), "shadow_review_status"))
                .containsExactlyInAnyOrder(null, "PASS", null, "FAIL");
        assertThat(values(tables.path("release_binding"), "action")).containsExactlyElementsOf(RELEASE_ACTIONS);
        assertThat(longValues(tables.path("release_binding"), "revision")).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(tables.path("release_binding").get(4).path("stable_version_id").asText())
                .isEqualTo("10000000-0000-0000-0000-000000000001");
        assertThat(fixture.toString())
                .doesNotContain("jdbc:", "password", "username", "apiKey", "api-key", "APP_DB_PASSWORD")
                .contains("04-影子灰度-科大讯飞经营事实.md", "05-影子灰度-金山办公风险事实.md");
    }

    /**
     * 测试场景：管理员使用正确短语导入内置演示数据。
     * 前置条件：当前状态为空，三套 preset 与两份授权材料均来自受版本控制目录。
     * 期望结果：服务生成 Task 5 v1 ZIP 并只调用其共享 validated import adapter；ZIP 含七表、两附件和三版本双冻结目录。
     * 断言重点：两份冻结目录 hash、fixture content_hash 和 version 的十二位后缀完全一致，DRAFT 不生成冻结目录。
     */
    @Test
    void buildsTask5SnapshotWithMatchingFrozenHashesAndAuthorizedAttachments() throws Exception {
        DemoStateService state = mock(DemoStateService.class);
        SnapshotArchiveService snapshots = mock(SnapshotArchiveService.class);
        AtomicReference<byte[]> imported = new AtomicReference<>();
        doAnswer(invocation -> {
                    try (InputStream input = invocation.getArgument(0)) {
                        imported.set(input.readAllBytes());
                    }
                    return null;
                })
                .when(snapshots)
                .importValidatedBuiltin(any());
        BuiltinDemoFixtureService service = service(state, snapshots, FIXTURE);

        service.importBuiltin("导入内置演示数据");

        verify(state).requireBuiltinImportConfirmationPhrase("导入内置演示数据");
        verify(state).requireBlank();
        verify(snapshots).importValidatedBuiltin(any());
        Path generatedArchive = temporaryRoot.resolve("generated-builtin-demo.zip");
        Files.write(generatedArchive, imported.get());
        SnapshotArchiveService validatedImporter = new SnapshotArchiveService(
                mock(DemoStateRepository.class),
                mock(DemoStateService.class),
                workbenchProperties(temporaryRoot.resolve("validated-storage")),
                properties(),
                OBJECT_MAPPER,
                mock(PlatformTransactionManager.class));
        assertThat(validatedImporter.validateAndStage(generatedArchive).manifest().formatVersion())
                .isEqualTo(SnapshotManifest.FORMAT_VERSION);
        Map<String, byte[]> entries = unzip(imported.get(), temporaryRoot.resolve("expanded"));
        JsonNode manifest = OBJECT_MAPPER.readTree(entries.get("manifest.json"));
        assertThat(manifest.path("formatVersion").asText()).isEqualTo(SnapshotManifest.FORMAT_VERSION);
        for (SnapshotTable table : SnapshotTable.values()) {
            assertThat(entries).containsKey("tables/" + table.tableName() + ".jsonl");
        }

        JsonNode fixture = OBJECT_MAPPER.readTree(FIXTURE.getInputStream());
        Map<String, JsonNode> skills = rowsById(fixture.path("tables").path("skill_version"));
        Map<String, String> presets = stringMap(fixture.path("frozenSkillPresets"));
        FrozenSkillLoader loader = new FrozenSkillLoader();
        presets.forEach((versionId, presetId) -> {
            String snapshotHash = loader.contentHash(temporaryRoot.resolve("expanded/files/skill-snapshots/" + versionId));
            String runtimeHash = loader.contentHash(temporaryRoot.resolve("expanded/files/skill-runtime/" + versionId));
            JsonNode row = skills.get(versionId);
            assertThat(snapshotHash).isEqualTo(runtimeHash).isEqualTo(row.path("content_hash").asText());
            assertThat(row.path("version").asText()).endsWith(snapshotHash.substring(0, 12));
        });
        assertThat(entries.keySet()).noneMatch(path -> path.contains("10000000-0000-0000-0000-000000000004/")
                && (path.contains("skill-snapshots") || path.contains("skill-runtime")));
        assertThat(entries.get("files/uploads/30000000-0000-0000-0000-000000000001/04-影子灰度-科大讯飞经营事实.md"))
                .isEqualTo(Files.readAllBytes(MATERIAL_ROOT.resolve("04-影子灰度-科大讯飞经营事实.md")));
        assertThat(entries.get("files/uploads/30000000-0000-0000-0000-000000000002/05-影子灰度-金山办公风险事实.md"))
                .isEqualTo(Files.readAllBytes(MATERIAL_ROOT.resolve("05-影子灰度-金山办公风险事实.md")));
    }

    /**
     * 测试场景：fixture 声明的冻结 content_hash 被意外修改。
     * 前置条件：preset 实际字节保持不变，仅把第一条 Skill 行的 64 位 hash 改成全零。
     * 期望结果：在调用共享导入器前以 DEMO_FIXTURE_SKILL_HASH_MISMATCH 拒绝。
     * 断言重点：数据库 content_hash、version 后缀、snapshot/runtime 目录任一不一致都不能进入导入事务。
     */
    @Test
    void rejectsFixtureWhenDeclaredSkillHashDoesNotMatchPresetBytes() throws Exception {
        DemoStateService state = mock(DemoStateService.class);
        SnapshotArchiveService snapshots = mock(SnapshotArchiveService.class);
        String original = Files.readString(Path.of("src/main/resources/demo-state/builtin-demo.json"));
        String tampered = original.replaceFirst(
                "6ddd7dd413ab70db4c045f64d78aa2c121f96ad0d5ab6ea5f7ac4cb4caf826ff",
                "0".repeat(64));

        assertThatThrownBy(() -> service(
                                state,
                                snapshots,
                                new ByteArrayResource(tampered.getBytes(StandardCharsets.UTF_8)))
                        .importBuiltin("导入内置演示数据"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("DEMO_FIXTURE_SKILL_HASH_MISMATCH"));

        verify(snapshots, never()).importValidatedBuiltin(any());
        assertStagingClean();
    }

    /**
     * 测试场景：当前数据库或三个受管目录已经非空，并连续两次请求内置导入。
     * 前置条件：Task 4/5 的统一空状态门禁每次都返回 DEMO_STATE_NOT_BLANK。
     * 期望结果：两次均稳定拒绝，既不生成导入 ZIP，也不调用共享导入事务。
     * 断言重点：内置演示只允许从零恢复，不能把重复请求解释为覆盖或幂等成功。
     */
    @Test
    void repeatedlyRejectsBuiltinImportWhenStateIsNotBlank() {
        DemoStateService state = mock(DemoStateService.class);
        SnapshotArchiveService snapshots = mock(SnapshotArchiveService.class);
        doThrow(new ServiceException("DEMO_STATE_NOT_BLANK", "当前比赛数据或运行目录不为空"))
                .when(state)
                .requireBlank();
        BuiltinDemoFixtureService service = service(state, snapshots, FIXTURE);

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> service.importBuiltin("导入内置演示数据"))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                            assertThat(exception.getCode()).isEqualTo("DEMO_STATE_NOT_BLANK"));
        }

        verify(snapshots, never()).importValidatedBuiltin(any());
        assertStagingClean();
    }

    /**
     * 测试场景：fixture 已完成 hash 与文件准备，但 Task 5 共享导入器在事务/目录交换阶段失败。
     * 前置条件：共享导入 adapter 抛出稳定业务异常，模拟其既有数据库回滚和目录恢复路径。
     * 期望结果：原异常透传，fixture 自己的临时构建目录全部删除，不执行第二套补偿或数据库清理。
     * 断言重点：Task 6 只委托 Task 5 的 staging+validated import，不新增 clearAll 或第二个目录交换实现。
     */
    @Test
    void cleansFixtureStagingWhenSharedValidatedImportFails() {
        DemoStateService state = mock(DemoStateService.class);
        SnapshotArchiveService snapshots = mock(SnapshotArchiveService.class);
        doThrow(new ServiceException("DEMO_SNAPSHOT_STORAGE_SWAP_FAILED", "快照运行目录安装失败"))
                .when(snapshots)
                .importValidatedBuiltin(any());

        assertThatThrownBy(() -> service(state, snapshots, FIXTURE).importBuiltin("导入内置演示数据"))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo("DEMO_SNAPSHOT_STORAGE_SWAP_FAILED"));

        verify(snapshots).importValidatedBuiltin(any());
        assertStagingClean();
        assertThat(Arrays.stream(BuiltinDemoFixtureService.class.getDeclaredConstructors())
                        .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                        .map(Class::getName))
                .noneMatch(name -> name.contains("Model") || name.contains("Mcp") || name.contains("Jdbc"));
    }

    /** 为每个用例创建只依赖受控资源、状态门禁和 Task 5 导入器的 fixture 服务。 */
    private BuiltinDemoFixtureService service(
            DemoStateService state, SnapshotArchiveService snapshots, Resource fixture) {
        return new BuiltinDemoFixtureService(
                OBJECT_MAPPER,
                new SkillPresetService(PRESET_ROOT),
                snapshots,
                state,
                properties(),
                fixture,
                temporaryRoot.resolve("fixture-staging"));
    }

    /** 构造测试专用路径与 Task 5 既有容量上限，不携带连接、账号或凭据字段。 */
    private static DemoAdminProperties properties() {
        return new DemoAdminProperties(true, MATERIAL_ROOT, PRESET_ROOT, 209_715_200L, 2_000, 524_288_000L);
    }

    /** 为 Task 5 真实校验器提供隔离 storageRoot；其余路径和连接字段不会在本测试中被访问。 */
    private static WorkbenchProperties workbenchProperties(Path storageRoot) {
        return new WorkbenchProperties(
                new WorkbenchProperties.DatabaseBoundary("demo", "test", false),
                storageRoot,
                Path.of("evals/manifest.json"),
                Path.of("skills/company-material-fact-check"),
                new WorkbenchProperties.Model("", "/v1/chat/completions", "", ""),
                java.net.URI.create("http://127.0.0.1:19091/mcp"));
    }

    /** 解压服务生成的可信测试 ZIP，同时把文件落盘供 FrozenSkillLoader 按正式算法核验。 */
    private static Map<String, byte[]> unzip(byte[] zipBytes, Path expandedRoot) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        Files.createDirectories(expandedRoot);
        try (ZipInputStream input = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] bytes = input.readAllBytes();
                entries.put(entry.getName(), bytes);
                if (entry.getName().startsWith("files/")) {
                    Path target = expandedRoot.resolve(entry.getName()).normalize();
                    Files.createDirectories(target.getParent());
                    Files.write(target, bytes);
                }
            }
        }
        return entries;
    }

    /** 把 JSON 数组中的文本或 null 字段按声明顺序投影为 Java 列表。 */
    private static List<String> values(JsonNode rows, String field) {
        List<String> values = new ArrayList<>();
        rows.forEach(row -> values.add(row.path(field).isMissingNode() || row.path(field).isNull()
                ? null
                : row.path(field).asText()));
        return values;
    }

    /** 把发布 revision 投影为 long 列表，用于同时锁定连续性与声明顺序。 */
    private static List<Long> longValues(JsonNode rows, String field) {
        List<Long> values = new ArrayList<>();
        rows.forEach(row -> values.add(row.path(field).asLong()));
        return values;
    }

    /** 读取单次评测实际持久化的 variants_json 标识，锁定 BASELINE/Stable/Candidate 的同条件组合。 */
    private static List<String> variantIdentifiers(JsonNode evaluation) {
        List<String> result = new ArrayList<>();
        evaluation.path("variants_json").forEach(variant -> result.add(variant.path("identifier").asText()));
        return result;
    }

    /** 将固定 UUID 行数组索引为 id，便于把目录 hash 与数据库行逐一关联。 */
    private static Map<String, JsonNode> rowsById(JsonNode rows) {
        Map<String, JsonNode> result = new HashMap<>();
        rows.forEach(row -> result.put(row.path("id").asText(), row));
        return result;
    }

    /** 将 fixture 的 versionId→presetId 对象转为不可变映射。 */
    private static Map<String, String> stringMap(JsonNode object) {
        Map<String, String> result = new HashMap<>();
        object.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        return Map.copyOf(result);
    }

    /** 所有失败路径都必须删除 Task 6 自己的构建暂存，不触碰 Task 5 管理的数据库或正式目录。 */
    private void assertStagingClean() {
        Path staging = temporaryRoot.resolve("fixture-staging");
        assertThat(staging).satisfies(path -> {
            if (Files.exists(path)) {
                try (var children = Files.list(path)) {
                    assertThat(children.toList()).isEmpty();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }
        });
    }
}
