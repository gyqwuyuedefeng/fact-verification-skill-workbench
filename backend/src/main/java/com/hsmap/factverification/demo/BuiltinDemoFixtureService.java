package com.hsmap.factverification.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsmap.factverification.agent.FrozenSkillLoader;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.skill.SkillReference;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * 把脱敏的固定 UUID fixture 组装为 Task 5 v1 快照并委托同一导入器恢复。
 *
 * <p>fixture 只声明七表关系、预置映射和授权材料文件名；Skill 正文/references 与附件字节始终从受版本控制目录复制。服务不依赖模型、MCP
 * 或当前数据库内容，也不引入第二套 SQL/事务/目录交换实现。
 */
@Service
public class BuiltinDemoFixtureService {

    private static final String FIXTURE_RESOURCE = "demo-state/builtin-demo.json";
    private static final String FIXTURE_FORMAT = "fact-verification-builtin-demo/v1";
    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final String TABLES_ROOT = "tables";
    private static final String FILES_ROOT = "files";

    private final ObjectMapper objectMapper;
    private final SkillPresetService presets;
    private final SnapshotArchiveService snapshots;
    private final DemoStateService stateService;
    private final DemoAdminProperties properties;
    private final Resource fixtureResource;
    private final Path stagingRoot;
    private final FrozenSkillLoader frozenSkillLoader = new FrozenSkillLoader();

    /** 生产装配固定使用 classpath fixture，并把短生命周期构建目录放在既有 storageRoot 内。 */
    @Autowired
    public BuiltinDemoFixtureService(
            ObjectMapper objectMapper,
            SkillPresetService presets,
            SnapshotArchiveService snapshots,
            DemoStateService stateService,
            DemoAdminProperties properties,
            WorkbenchProperties workbenchProperties) {
        this(
                objectMapper,
                presets,
                snapshots,
                stateService,
                properties,
                new ClassPathResource(FIXTURE_RESOURCE),
                workbenchProperties.storageRoot().resolve(".demo-builtin"));
    }

    /** 包内测试可替换只读 fixture 和短生命周期根，生产仍使用固定 classpath 资源。 */
    BuiltinDemoFixtureService(
            ObjectMapper objectMapper,
            SkillPresetService presets,
            SnapshotArchiveService snapshots,
            DemoStateService stateService,
            DemoAdminProperties properties,
            Resource fixtureResource,
            Path stagingRoot) {
        this.objectMapper = objectMapper;
        this.presets = presets;
        this.snapshots = snapshots;
        this.stateService = stateService;
        this.properties = properties;
        this.fixtureResource = fixtureResource;
        this.stagingRoot = stagingRoot.toAbsolutePath().normalize();
    }

    /**
     * 仅在确认语正确且七表/三目录为空时导入固定演示状态。
     *
     * <p>首次空白检查在任何临时文件创建前执行；Task 5 导入器还会在管理写锁和七表锁内再次检查，关闭并发写入窗口。
     */
    public void importBuiltin(String confirmationPhrase) {
        stateService.requireBuiltinImportConfirmationPhrase(confirmationPhrase);
        stateService.requireBlank();
        Path operationRoot = null;
        try {
            Files.createDirectories(stagingRoot);
            operationRoot = Files.createTempDirectory(stagingRoot, "fixture-");
            Path archive = buildArchive(operationRoot);
            try (InputStream input = Files.newInputStream(archive)) {
                snapshots.importValidatedBuiltin(input);
            }
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceException("DEMO_FIXTURE_BUILD_FAILED", "内置演示数据构建失败");
        } finally {
            deleteTreeQuietly(operationRoot);
        }
    }

    /** 读取并校验声明后，在独立临时根中构造 Task 5 唯一认识的 manifest、七表 JSONL 与三个受管文件根。 */
    private Path buildArchive(Path operationRoot) throws IOException {
        ObjectNode fixture = readFixture();
        ObjectNode tables = requireObject(fixture, "tables");
        requireExactTables(tables);
        ObjectNode skillPresets = requireObject(fixture, "skillPresets");
        ObjectNode frozenPresets = requireObject(fixture, "frozenSkillPresets");
        Path filesRoot = operationRoot.resolve(FILES_ROOT);
        Files.createDirectories(filesRoot);
        copyFrozenSkills(tables, skillPresets, frozenPresets, filesRoot);
        copyAttachments(fixture, tables, filesRoot);

        Map<String, byte[]> tableFiles = tableFiles(tables, skillPresets);
        List<SnapshotManifest.FileEntry> managedFiles = describeManagedFiles(filesRoot);
        Map<String, SnapshotManifest.TableEntry> tableEntries = new LinkedHashMap<>();
        for (SnapshotTable table : SnapshotTable.values()) {
            byte[] bytes = tableFiles.get(table.tableName());
            tableEntries.put(
                    table.tableName(), new SnapshotManifest.TableEntry(countRows(tables, table), sha256(bytes)));
        }
        SnapshotManifest manifest = new SnapshotManifest(
                SnapshotManifest.FORMAT_VERSION, Instant.parse(requiredText(fixture, "createdAt")), tableEntries, managedFiles);
        Path archive = operationRoot.resolve("builtin-demo.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            for (SnapshotTable table : SnapshotTable.values()) {
                writeEntry(zip, TABLES_ROOT + "/" + table.tableName() + ".jsonl", tableFiles.get(table.tableName()));
            }
            for (SnapshotManifest.FileEntry file : managedFiles) {
                writeEntry(zip, FILES_ROOT + "/" + file.path(), Files.readAllBytes(filesRoot.resolve(file.path())));
            }
            writeEntry(zip, MANIFEST_ENTRY, objectMapper.writeValueAsBytes(manifest));
        }
        return archive;
    }

    /**
     * 为三个非 DRAFT 版本分别生成 snapshot/runtime，并用正式 loader 同时校验两目录、fixture hash 和版本后缀。
     */
    private void copyFrozenSkills(
            ObjectNode tables, ObjectNode skillPresets, ObjectNode frozenPresets, Path filesRoot) {
        Map<String, ObjectNode> skillRows = rowsById(requireArray(tables, SnapshotTable.SKILL_VERSION.tableName()));
        frozenPresets.fields().forEachRemaining(entry -> {
            String versionId = requireUuidText(entry.getKey());
            String presetId = requireTextValue(entry.getValue(), "frozenSkillPresets." + versionId);
            if (!presetId.equals(requireTextValue(skillPresets.get(versionId), "skillPresets." + versionId))) {
                throw skillHashMismatch();
            }
            ObjectNode row = skillRows.get(versionId);
            if (row == null || "DRAFT".equals(requiredText(row, "status"))) {
                throw skillHashMismatch();
            }
            Path snapshotRoot = filesRoot.resolve("skill-snapshots").resolve(versionId);
            Path runtimeRoot = filesRoot.resolve("skill-runtime").resolve(versionId);
            presets.copyPreset(presetId, snapshotRoot);
            presets.copyPreset(presetId, runtimeRoot);
            String snapshotHash = frozenSkillLoader.contentHash(snapshotRoot);
            String runtimeHash = frozenSkillLoader.contentHash(runtimeRoot);
            String declaredHash = requiredText(row, "content_hash");
            String version = requiredText(row, "version");
            if (!snapshotHash.equals(runtimeHash)
                    || !snapshotHash.equals(declaredHash)
                    || snapshotHash.length() != 64
                    || !version.endsWith(snapshotHash.substring(0, 12))) {
                throw skillHashMismatch();
            }
        });
        long nonDraft = skillRows.values().stream()
                .filter(row -> !"DRAFT".equals(requiredText(row, "status")))
                .count();
        if (frozenPresets.size() != nonDraft) {
            throw skillHashMismatch();
        }
    }

    /** 只复制 fixture 明确声明的两份授权演示材料，并校验目标仍是固定 taskId 两级结构。 */
    private void copyAttachments(ObjectNode fixture, ObjectNode tables, Path filesRoot) throws IOException {
        Map<String, ObjectNode> taskRows = rowsById(requireArray(tables, SnapshotTable.VERIFICATION_TASK.tableName()));
        ArrayNode attachments = requireArray(fixture, "attachments");
        for (JsonNode value : attachments) {
            ObjectNode attachment = requireObjectValue(value, "attachments");
            String taskId = requireUuidText(requiredText(attachment, "taskId"));
            String sourceFile = requiredText(attachment, "sourceFile");
            if (!List.of(
                            "04-影子灰度-科大讯飞经营事实.md",
                            "05-影子灰度-金山办公风险事实.md")
                    .contains(sourceFile)) {
                throw new ServiceException("DEMO_FIXTURE_ATTACHMENT_INVALID", "内置演示附件不在授权白名单");
            }
            Path materialRoot = properties.demoMaterialRoot().toAbsolutePath().normalize();
            Path source = materialRoot.resolve(sourceFile).normalize();
            if (!source.startsWith(materialRoot) || !Files.isRegularFile(source)) {
                throw new ServiceException("DEMO_FIXTURE_ATTACHMENT_INVALID", "内置演示附件缺失或路径无效");
            }
            byte[] bytes = Files.readAllBytes(source);
            ObjectNode task = taskRows.get(taskId);
            String expectedUpload = "uploads/" + taskId + "/" + sourceFile;
            if (task == null
                    || !sourceFile.equals(requiredText(task, "original_file_name"))
                    || !expectedUpload.equals(requiredText(task, "upload_path"))
                    || requiredLong(task, "file_size") != bytes.length
                    || !sha256(bytes).equals(requiredText(task, "file_hash"))) {
                throw new ServiceException("DEMO_FIXTURE_ATTACHMENT_INVALID", "内置演示任务与附件字节不一致");
            }
            Path target = filesRoot.resolve("uploads").resolve(taskId).resolve(sourceFile).normalize();
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        }
        if (attachments.size() != taskRows.size()) {
            throw new ServiceException("DEMO_FIXTURE_ATTACHMENT_INVALID", "内置演示任务与授权附件数量不一致");
        }
    }

    /** 将声明行转换为 Task 5 JSONL；Skill 文本与 references 强制由 preset 覆盖，不信任 fixture 中的重复副本。 */
    private Map<String, byte[]> tableFiles(ObjectNode tables, ObjectNode skillPresets) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (SnapshotTable table : SnapshotTable.values()) {
            ArrayNode rows = requireArray(tables, table.tableName());
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            for (JsonNode value : rows) {
                ObjectNode row = requireObjectValue(value, table.tableName()).deepCopy();
                if (table == SnapshotTable.SKILL_VERSION) {
                    String id = requiredText(row, "id");
                    JsonNode presetValue = skillPresets.get(id);
                    if (presetValue == null) {
                        throw new ServiceException("DEMO_FIXTURE_SKILL_PRESET_MISSING", "内置 Skill 行缺少预置映射");
                    }
                    SkillPresetService.SkillPreset preset = presets.preset(requireTextValue(presetValue, id));
                    row.put("skill_markdown", preset.skillMarkdown());
                    row.set("references_json", referencesJson(preset.references()));
                }
                output.write(objectMapper.writeValueAsBytes(row));
                output.write('\n');
            }
            result.put(table.tableName(), output.toByteArray());
        }
        return Map.copyOf(result);
    }

    /** 把 API 展示模型转换为数据库既有 references_json 数组，路径和内容均来自同一预置读取结果。 */
    private ArrayNode referencesJson(List<SkillReference> references) {
        ArrayNode result = objectMapper.createArrayNode();
        for (SkillReference reference : references) {
            ObjectNode item = result.addObject();
            item.put("path", reference.path());
            item.put("content", reference.content());
        }
        return result;
    }

    /** 按 ZIP 相对路径稳定排序，摘要覆盖实际复制后的每个受管文件字节。 */
    private List<SnapshotManifest.FileEntry> describeManagedFiles(Path filesRoot) throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(filesRoot)) {
            paths = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> archivePath(filesRoot.relativize(path))))
                    .toList();
        }
        List<SnapshotManifest.FileEntry> result = new ArrayList<>();
        for (Path path : paths) {
            byte[] bytes = Files.readAllBytes(path);
            result.add(new SnapshotManifest.FileEntry(
                    archivePath(filesRoot.relativize(path)), bytes.length, sha256(bytes)));
        }
        return List.copyOf(result);
    }

    /** 读取固定 classpath JSON；只允许当前 v1 fixture 格式。 */
    private ObjectNode readFixture() {
        try (InputStream input = fixtureResource.getInputStream()) {
            JsonNode value = objectMapper.readTree(input);
            ObjectNode fixture = requireObjectValue(value, "fixture");
            if (!FIXTURE_FORMAT.equals(requiredText(fixture, "formatVersion"))) {
                throw new ServiceException("DEMO_FIXTURE_VERSION_INVALID", "内置演示数据版本不受支持");
            }
            return fixture;
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceException("DEMO_FIXTURE_INVALID", "内置演示数据无法读取");
        }
    }

    private static ObjectNode requireObject(ObjectNode parent, String field) {
        return requireObjectValue(parent.get(field), field);
    }

    private static ObjectNode requireObjectValue(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw new ServiceException("DEMO_FIXTURE_INVALID", "内置演示数据对象字段无效：" + field);
        }
        return (ObjectNode) value;
    }

    private static ArrayNode requireArray(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) {
            throw new ServiceException("DEMO_FIXTURE_INVALID", "内置演示数据数组字段无效：" + field);
        }
        return (ArrayNode) value;
    }

    private static String requiredText(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        return requireTextValue(value, field);
    }

    /** 读取必须存在的整数值，避免字符串或浮点数在附件长度校验中被静默截断。 */
    private static long requiredLong(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new ServiceException("DEMO_FIXTURE_INVALID", "内置演示数据整数值无效：" + field);
        }
        return value.longValue();
    }

    private static String requireTextValue(JsonNode value, String field) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ServiceException("DEMO_FIXTURE_INVALID", "内置演示数据文本字段无效：" + field);
        }
        return value.asText();
    }

    private static String requireUuidText(String value) {
        try {
            UUID.fromString(value);
            return value;
        } catch (IllegalArgumentException exception) {
            throw new ServiceException("DEMO_FIXTURE_INVALID", "内置演示数据 UUID 无效");
        }
    }

    private static Map<String, ObjectNode> rowsById(ArrayNode rows) {
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        for (JsonNode value : rows) {
            ObjectNode row = requireObjectValue(value, "skill_version");
            String id = requireUuidText(requiredText(row, "id"));
            if (result.put(id, row) != null) {
                throw new ServiceException("DEMO_FIXTURE_INVALID", "内置演示数据包含重复 UUID");
            }
        }
        return result;
    }

    /** fixture 只能声明 Task 5 已存在的固定七表，不能悄然扩展成第二套快照格式。 */
    private static void requireExactTables(ObjectNode tables) {
        java.util.Set<String> expected = java.util.Arrays.stream(SnapshotTable.values())
                .map(SnapshotTable::tableName)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> actual = new java.util.HashSet<>();
        tables.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new ServiceException("DEMO_FIXTURE_INVALID", "内置演示数据必须恰好包含固定七表");
        }
    }

    private static long countRows(ObjectNode tables, SnapshotTable table) {
        return requireArray(tables, table.tableName()).size();
    }

    private static ServiceException skillHashMismatch() {
        return new ServiceException("DEMO_FIXTURE_SKILL_HASH_MISMATCH", "内置演示 Skill 版本哈希与预置内容不一致");
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            input.transferTo(zip);
        }
        zip.closeEntry();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256", exception);
        }
    }

    private static String archivePath(Path path) {
        StringBuilder result = new StringBuilder();
        for (Path part : path) {
            if (!result.isEmpty()) {
                result.append('/');
            }
            result.append(part);
        }
        return result.toString();
    }

    /** 只删除本服务刚创建的随机 operationRoot；失败不会调用 clearAll 或触碰 Task 5 正式目录。 */
    private static void deleteTreeQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // 主异常优先；临时目录清理失败不会触发破坏性数据库或正式目录补偿。
        }
    }
}
