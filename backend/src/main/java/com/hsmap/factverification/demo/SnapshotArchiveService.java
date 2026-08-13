package com.hsmap.factverification.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.shared.ServiceException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * test-profile 比赛演示状态的受控快照服务。
 *
 * <p>服务只导出固定七表与 uploads、skill-snapshots、skill-runtime 三个目录。导入把 ZIP 视为不可信输入，先在本次
 * `.demo-import/&lt;operationId&gt;` 中完成容量、路径、版本、行数、size 与 SHA-256 校验，再开始数据库事务和正式目录交换。
 */
@Service
public class SnapshotArchiveService {

    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final String TABLES_ROOT = "tables";
    private static final String FILES_ROOT = "files";
    private static final String GIT_KEEP = ".gitkeep";
    private static final String IMPORT_ROOT = ".demo-import";
    private static final List<String> MANAGED_DIRECTORIES = List.of("uploads", "skill-snapshots", "skill-runtime");
    private static final List<SnapshotTable> IMPORT_ORDER = List.of(
            SnapshotTable.SKILL_VERSION,
            SnapshotTable.EVALUATION_RUN,
            SnapshotTable.VERIFICATION_TASK,
            SnapshotTable.VERIFICATION_RUN,
            SnapshotTable.CLAIM,
            SnapshotTable.EVIDENCE_SNAPSHOT,
            SnapshotTable.RELEASE_BINDING);
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:.*");

    private final DemoStateRepository repository;
    private final DemoStateService stateService;
    private final Path storageRoot;
    private final long maxArchiveBytes;
    private final int maxEntryCount;
    private final long maxExpandedBytes;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate importTransaction;
    private final TransactionTemplate cleanupTransaction;

    /**
     * 注入应用仓储、Task 4 状态门禁、既有 storageRoot、受控上限与数据库事务管理器。
     *
     * <p>两个 REQUIRES_NEW 模板分别负责正式导入和失败补偿，避免继承 HTTP 上下文中不相关的业务事务。
     */
    public SnapshotArchiveService(
            DemoStateRepository repository,
            DemoStateService stateService,
            WorkbenchProperties workbenchProperties,
            DemoAdminProperties adminProperties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.stateService = stateService;
        this.storageRoot = workbenchProperties.storageRoot().toAbsolutePath().normalize();
        this.maxArchiveBytes = adminProperties.maxArchiveBytes();
        this.maxEntryCount = adminProperties.maxEntryCount();
        this.maxExpandedBytes = adminProperties.maxExpandedBytes();
        this.objectMapper = objectMapper;
        this.importTransaction = requiresNew(transactionManager);
        this.cleanupTransaction = requiresNew(transactionManager);
    }

    /**
     * 向 HTTP 响应流直接写出 v1 ZIP，不在内存中聚合完整压缩包。
     *
     * <p>活动工作检查先于 ZIP header；数据库按行写 JSONL，文件按固定目录遍历并计算实际读取字节摘要，manifest 最后写入。
     */
    public void exportTo(OutputStream output) {
        stateService.requireQuiescentForSnapshotExport();
        try {
            Files.createDirectories(storageRoot);
            ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8);
            Map<String, SnapshotManifest.TableEntry> tables = exportTables(zip);
            List<SnapshotManifest.FileEntry> files = exportManagedFiles(zip);
            writeEntry(
                    zip,
                    MANIFEST_ENTRY,
                    objectMapper.writeValueAsBytes(
                            new SnapshotManifest(SnapshotManifest.FORMAT_VERSION, Instant.now(), tables, files)));
            zip.finish();
            zip.flush();
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_EXPORT_FAILED", "比赛状态快照导出失败");
        }
    }

    /**
     * 从原始 application/zip 请求流恢复比赛状态。
     *
     * <p>确认语和首次空白检查先于读取请求；完整暂存校验后再次检查空白，随后在事务回调中按固定顺序写表并交换目录。
     */
    public void importFrom(InputStream input, String confirmationPhrase) {
        stateService.requireImportConfirmationPhrase(confirmationPhrase);
        stateService.requireBlank();
        UUID operationId = UUID.randomUUID();
        Path operationRoot = operationRoot(operationId);
        try {
            Files.createDirectories(operationRoot);
            Path archivePath = copyArchive(input, operationRoot.resolve("archive.zip"));
            StagedSnapshot staged = validateStagedArchive(operationRoot, archivePath);
            preflightImportRows(staged);
            stateService.requireBlank();
            applyValidatedSnapshot(staged);
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_IMPORT_FAILED", "比赛状态快照导入失败");
        } finally {
            deleteTreeQuietly(operationRoot);
        }
    }

    /**
     * 把磁盘上的测试 ZIP 复制到独立操作目录并执行与 HTTP 导入完全相同的安全校验。
     *
     * <p>该包内入口只供聚焦安全测试使用，不写数据库、不交换正式目录；成功暂存由测试临时根随用例回收。
     */
    StagedSnapshot validateAndStage(Path zipPath) {
        UUID operationId = UUID.randomUUID();
        Path operationRoot = operationRoot(operationId);
        try {
            Files.createDirectories(operationRoot);
            try (InputStream input = Files.newInputStream(zipPath)) {
                Path archivePath = copyArchive(input, operationRoot.resolve("archive.zip"));
                return validateStagedArchive(operationRoot, archivePath);
            }
        } catch (ServiceException exception) {
            deleteTreeQuietly(operationRoot);
            throw exception;
        } catch (IOException exception) {
            deleteTreeQuietly(operationRoot);
            throw new ServiceException("DEMO_SNAPSHOT_IMPORT_FAILED", "比赛状态快照暂存失败");
        }
    }

    /** 按枚举顺序写七个 JSONL entry，并记录实际行数与完整文件摘要。 */
    private Map<String, SnapshotManifest.TableEntry> exportTables(ZipOutputStream zip) throws IOException {
        Map<String, SnapshotManifest.TableEntry> result = new LinkedHashMap<>();
        for (SnapshotTable table : SnapshotTable.values()) {
            zip.putNextEntry(new ZipEntry(tableEntryName(table)));
            MessageDigest digest = sha256Digest();
            long[] rows = {0L};
            repository.exportRows(table, json -> {
                String normalized = table == SnapshotTable.VERIFICATION_TASK ? normalizeExportTaskRow(json) : json;
                byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
                zip.write(bytes);
                zip.write('\n');
                digest.update(bytes);
                digest.update((byte) '\n');
                rows[0]++;
            });
            zip.closeEntry();
            result.put(table.tableName(), new SnapshotManifest.TableEntry(rows[0], hex(digest.digest())));
        }
        return Map.copyOf(result);
    }

    /** 遍历三个受管目录，忽略 Git 边界和本服务暂存物，并拒绝可能越界读取的符号链接。 */
    private List<SnapshotManifest.FileEntry> exportManagedFiles(ZipOutputStream zip) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String directoryName : MANAGED_DIRECTORIES) {
            Path directory = requireWithinStorageRoot(storageRoot.resolve(directoryName));
            if (!Files.exists(directory)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path path : paths.sorted().toList()) {
                    Path relative = storageRoot.relativize(path);
                    if (containsIgnoredSegment(relative)) {
                        continue;
                    }
                    if (Files.isSymbolicLink(path)) {
                        throw new ServiceException("DEMO_SNAPSHOT_FILE_INVALID", "受管目录包含不允许导出的符号链接");
                    }
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        files.add(path);
                    }
                }
            }
        }
        files.sort(Comparator.comparing(path -> archivePath(storageRoot.relativize(path))));
        List<SnapshotManifest.FileEntry> manifestFiles = new ArrayList<>();
        for (Path file : files) {
            Path relative = storageRoot.relativize(file);
            String manifestPath = archivePath(relative);
            zip.putNextEntry(new ZipEntry(FILES_ROOT + "/" + manifestPath));
            MessageDigest digest = sha256Digest();
            long size = 0L;
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    zip.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    size += read;
                }
            }
            zip.closeEntry();
            manifestFiles.add(new SnapshotManifest.FileEntry(manifestPath, size, hex(digest.digest())));
        }
        return List.copyOf(manifestFiles);
    }

    /** 把 verification_task.upload_path 变为环境无关的 uploads/{taskId}/{fileName}。 */
    private String normalizeExportTaskRow(String json) {
        try {
            ObjectNode row = requireObjectRow(json);
            String taskId = requiredText(row, "id");
            Path upload =
                    Path.of(requiredText(row, "upload_path")).toAbsolutePath().normalize();
            Path uploadsRoot = requireWithinStorageRoot(storageRoot.resolve("uploads"));
            if (!upload.startsWith(uploadsRoot)
                    || Files.isSymbolicLink(upload)
                    || !Files.isRegularFile(upload, LinkOption.NOFOLLOW_LINKS)) {
                throw new ServiceException("DEMO_SNAPSHOT_UPLOAD_PATH_INVALID", "任务上传文件不在当前受管 uploads 目录");
            }
            Path relative = uploadsRoot.relativize(upload);
            if (relative.getNameCount() != 2
                    || !taskId.equals(relative.getName(0).toString())) {
                throw new ServiceException("DEMO_SNAPSHOT_UPLOAD_PATH_INVALID", "任务上传路径不符合固定 taskId 目录结构");
            }
            Path realRoot = uploadsRoot.toRealPath();
            if (!upload.toRealPath().startsWith(realRoot)) {
                throw new ServiceException("DEMO_SNAPSHOT_UPLOAD_PATH_INVALID", "任务上传文件越出当前受管 uploads 目录");
            }
            row.put("upload_path", "uploads/" + taskId + "/" + relative.getFileName());
            return objectMapper.writeValueAsString(row);
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException | InvalidPathException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_UPLOAD_PATH_INVALID", "任务上传路径无法安全归一化");
        }
    }

    /** 流式复制原始请求并独立累计压缩包字节，超过上限时不再读取或解析 ZIP。 */
    private Path copyArchive(InputStream input, Path archivePath) throws IOException {
        long total = 0L;
        try (OutputStream output = Files.newOutputStream(archivePath)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxArchiveBytes) {
                    throw new ServiceException("DEMO_SNAPSHOT_ARCHIVE_TOO_LARGE", "快照压缩包大小超过限制");
                }
                output.write(buffer, 0, read);
            }
        }
        return archivePath;
    }

    /** 解压到本次 expanded 根并同时执行 entry 计数、路径、重复与累计展开体积校验。 */
    private StagedSnapshot validateStagedArchive(Path operationRoot, Path archivePath) throws IOException {
        Path expandedRoot = operationRoot.resolve("expanded").normalize();
        requireWithinOperation(operationRoot, expandedRoot);
        Files.createDirectories(expandedRoot);
        Map<String, StagedEntry> entries = new LinkedHashMap<>();
        long expandedBytes = 0L;
        int entryCount = 0;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archivePath), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > maxEntryCount) {
                    throw new ServiceException("DEMO_SNAPSHOT_ENTRY_LIMIT_EXCEEDED", "快照文件数量超过限制");
                }
                String normalizedName = validateEntryName(entry.getName(), entry.isDirectory());
                if (entries.containsKey(normalizedName)) {
                    throw new ServiceException("DEMO_SNAPSHOT_DUPLICATE_ENTRY", "快照包含规范化后的重复文件");
                }
                if (entry.getSize() >= 0 && entry.getSize() > maxExpandedBytes - expandedBytes) {
                    throw new ServiceException("DEMO_SNAPSHOT_EXPANDED_TOO_LARGE", "快照累计展开大小超过限制");
                }
                Path target = requireWithinOperation(
                        operationRoot, expandedRoot.resolve(normalizedName).normalize());
                if (!target.startsWith(expandedRoot)) {
                    throw illegalPath();
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream output = Files.newOutputStream(target)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            expandedBytes += read;
                            if (expandedBytes > maxExpandedBytes) {
                                throw new ServiceException("DEMO_SNAPSHOT_EXPANDED_TOO_LARGE", "快照累计展开大小超过限制");
                            }
                            output.write(buffer, 0, read);
                        }
                    }
                }
                entries.put(normalizedName, new StagedEntry(target, entry.isDirectory()));
                input.closeEntry();
            }
        }
        SnapshotManifest manifest = validateManifest(expandedRoot, entries);
        return new StagedSnapshot(operationRoot, expandedRoot, manifest);
    }

    /** 校验固定根与路径语法；任一文本歧义都在解析到磁盘前失败关闭。 */
    private String validateEntryName(String rawName, boolean directory) {
        if (rawName == null
                || rawName.isBlank()
                || rawName.indexOf('\0') >= 0
                || rawName.indexOf('\\') >= 0
                || rawName.startsWith("/")
                || WINDOWS_ABSOLUTE_PATH.matcher(rawName).matches()) {
            throw illegalPath();
        }
        try {
            Path rawPath = Path.of(rawName);
            if (rawPath.isAbsolute()) {
                throw illegalPath();
            }
            for (Path part : rawPath) {
                if ("..".equals(part.toString())) {
                    throw illegalPath();
                }
            }
            Path normalized = rawPath.normalize();
            if (normalized.getNameCount() == 0) {
                throw illegalPath();
            }
            String name = archivePath(normalized);
            if (!isAllowedEntry(name, directory)) {
                throw illegalPath();
            }
            return name;
        } catch (InvalidPathException exception) {
            throw illegalPath();
        }
    }

    /** 固定 ZIP 根：manifest、七表 JSONL 以及 files 下三个受管目录。 */
    private boolean isAllowedEntry(String name, boolean directory) {
        Path path = Path.of(name);
        String root = path.getName(0).toString();
        if (MANIFEST_ENTRY.equals(name)) {
            return !directory;
        }
        if (TABLES_ROOT.equals(root)) {
            if (directory) {
                return path.getNameCount() == 1;
            }
            if (path.getNameCount() != 2 || !name.endsWith(".jsonl")) {
                return false;
            }
            String tableName = path.getFileName()
                    .toString()
                    .substring(0, path.getFileName().toString().length() - 6);
            return SnapshotTable.fromTableName(tableName).isPresent();
        }
        if (!FILES_ROOT.equals(root)) {
            return false;
        }
        if (path.getNameCount() == 1) {
            return directory;
        }
        String managedRoot = path.getName(1).toString();
        if (!MANAGED_DIRECTORIES.contains(managedRoot)) {
            return false;
        }
        for (Path part : path) {
            if (GIT_KEEP.equals(part.toString()) || part.toString().startsWith(".demo-")) {
                return false;
            }
        }
        return directory || path.getNameCount() >= 3;
    }

    /** 校验 manifest 和所有声明/实际表文件、业务文件的一一对应完整性。 */
    private SnapshotManifest validateManifest(Path expandedRoot, Map<String, StagedEntry> entries) throws IOException {
        StagedEntry manifestEntry = entries.get(MANIFEST_ENTRY);
        if (manifestEntry == null || manifestEntry.directory()) {
            throw new ServiceException("DEMO_SNAPSHOT_MANIFEST_MISSING", "快照文件缺失 manifest.json");
        }
        SnapshotManifest manifest;
        try {
            manifest = objectMapper.readValue(manifestEntry.path().toFile(), SnapshotManifest.class);
        } catch (IOException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_MANIFEST_INVALID", "快照 manifest 无法解析");
        }
        if (!SnapshotManifest.FORMAT_VERSION.equals(manifest.formatVersion())) {
            throw new ServiceException("DEMO_SNAPSHOT_VERSION_UNSUPPORTED", "快照版本不受支持");
        }
        if (manifest.createdAt() == null || manifest.tables() == null || manifest.files() == null) {
            throw new ServiceException("DEMO_SNAPSHOT_MANIFEST_INVALID", "快照 manifest 字段不完整");
        }
        Set<String> expectedTables = new LinkedHashSet<>();
        for (SnapshotTable table : SnapshotTable.values()) {
            expectedTables.add(table.tableName());
        }
        if (!manifest.tables().keySet().equals(expectedTables)) {
            throw new ServiceException("DEMO_SNAPSHOT_TABLE_SET_INVALID", "快照表白名单不完整或包含未知表");
        }
        for (SnapshotTable table : SnapshotTable.values()) {
            SnapshotManifest.TableEntry declared = manifest.tables().get(table.tableName());
            StagedEntry actual = entries.get(tableEntryName(table));
            if (actual == null || actual.directory()) {
                throw new ServiceException("DEMO_SNAPSHOT_FILE_MISSING", "快照文件缺失：" + tableEntryName(table));
            }
            if (declared == null || declared.rows() < 0 || !validSha(declared.sha256())) {
                throw new ServiceException("DEMO_SNAPSHOT_MANIFEST_INVALID", "快照表清单字段无效");
            }
            long rows = validateJsonLines(actual.path());
            if (rows != declared.rows()) {
                throw new ServiceException("DEMO_SNAPSHOT_ROW_COUNT_MISMATCH", "快照表 JSONL 行数与 manifest 不一致");
            }
            if (!sha256(actual.path()).equals(declared.sha256())) {
                throw new ServiceException("DEMO_SNAPSHOT_SHA256_MISMATCH", "快照表 SHA-256 与 manifest 不一致");
            }
        }
        validateManifestFiles(expandedRoot, entries, manifest.files());
        return manifest;
    }

    /** 校验文件 path、size、SHA-256，且实际 files entry 集合不能多也不能少。 */
    private void validateManifestFiles(
            Path expandedRoot, Map<String, StagedEntry> entries, List<SnapshotManifest.FileEntry> declaredFiles)
            throws IOException {
        Set<String> expectedEntries = new HashSet<>();
        Set<String> declaredPaths = new HashSet<>();
        for (SnapshotManifest.FileEntry file : declaredFiles) {
            if (file == null || file.size() < 0 || !validSha(file.sha256())) {
                throw new ServiceException("DEMO_SNAPSHOT_MANIFEST_INVALID", "快照文件清单字段无效");
            }
            String relative = validateManifestFilePath(file.path());
            if (!declaredPaths.add(relative)) {
                throw new ServiceException("DEMO_SNAPSHOT_DUPLICATE_ENTRY", "manifest 包含重复文件");
            }
            String entryName = FILES_ROOT + "/" + relative;
            expectedEntries.add(entryName);
            StagedEntry actual = entries.get(entryName);
            if (actual == null || actual.directory()) {
                throw new ServiceException("DEMO_SNAPSHOT_FILE_MISSING", "快照文件缺失：" + relative);
            }
            Path actualPath = requireWithinOperation(expandedRoot, actual.path());
            if (Files.size(actualPath) != file.size()) {
                throw new ServiceException("DEMO_SNAPSHOT_FILE_SIZE_MISMATCH", "快照文件 size 与 manifest 不一致");
            }
            if (!sha256(actualPath).equals(file.sha256())) {
                throw new ServiceException("DEMO_SNAPSHOT_SHA256_MISMATCH", "快照文件 SHA-256 与 manifest 不一致");
            }
        }
        Set<String> actualEntries = new HashSet<>();
        entries.forEach((name, entry) -> {
            if (!entry.directory() && name.startsWith(FILES_ROOT + "/")) {
                actualEntries.add(name);
            }
        });
        if (!actualEntries.equals(expectedEntries)) {
            throw new ServiceException("DEMO_SNAPSHOT_FILE_SET_INVALID", "快照业务文件与 manifest 声明不一致");
        }
    }

    /** manifest 文件路径不含 files 前缀，但采用与 ZIP entry 相同的受管根和遍历规则。 */
    private String validateManifestFilePath(String rawPath) {
        if (rawPath == null || rawPath.startsWith(FILES_ROOT + "/")) {
            throw illegalPath();
        }
        String validated = validateEntryName(FILES_ROOT + "/" + rawPath, false);
        return validated.substring((FILES_ROOT + "/").length());
    }

    /** 逐行确认 JSONL 每行都是 JSON object，并返回精确非空行数。 */
    private long validateJsonLines(Path path) throws IOException {
        long rows = 0L;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    throw new ServiceException("DEMO_SNAPSHOT_JSONL_INVALID", "快照表 JSONL 包含空行");
                }
                try {
                    JsonNode node = objectMapper.readTree(line);
                    if (node == null || !node.isObject()) {
                        throw new ServiceException("DEMO_SNAPSHOT_JSONL_INVALID", "快照表 JSONL 行不是 JSON object");
                    }
                } catch (ServiceException exception) {
                    throw exception;
                } catch (IOException exception) {
                    throw new ServiceException("DEMO_SNAPSHOT_JSONL_INVALID", "快照表 JSONL 无法解析");
                }
                rows++;
            }
        }
        return rows;
    }

    /**
     * 在触碰正式数据库前完成两个需业务语义解释的 JSONL 检查。
     *
     * <p>Skill 的循环引用 UUID 与任务上传相对路径不能只依赖数据库事务回滚兜底；预检确保它们都能安全解析并命中已校验暂存文件。
     */
    private void preflightImportRows(StagedSnapshot staged) {
        readRows(staged, SnapshotTable.SKILL_VERSION, json -> {
            ObjectNode row = requireObjectRow(json);
            requiredUuid(row, "id");
            optionalUuid(row, "parent_version_id");
            optionalUuid(row, "registered_evaluation_id");
        });
        readRows(staged, SnapshotTable.VERIFICATION_TASK, json -> normalizeImportedTaskRow(staged, json));
    }

    /** 在独立事务内按固定顺序插入行；任一异常都执行数据库与正式目录空白补偿。 */
    private void applyValidatedSnapshot(StagedSnapshot staged) {
        AtomicBoolean fileInstallStarted = new AtomicBoolean(false);
        try {
            importTransaction.executeWithoutResult(status -> {
                List<SkillReferenceRestore> references = importSkillRows(staged);
                importTable(staged, SnapshotTable.EVALUATION_RUN);
                references.forEach(reference -> repository.restoreSkillReferences(
                        reference.id(), reference.parentVersionId(), reference.registeredEvaluationId()));
                for (SnapshotTable table : IMPORT_ORDER.subList(2, IMPORT_ORDER.size())) {
                    importTable(staged, table);
                }
                fileInstallStarted.set(true);
                installManagedDirectories(staged);
            });
        } catch (RuntimeException exception) {
            compensateFailedImport(fileInstallStarted.get(), exception);
            throw exception;
        }
    }

    /** Skill 首次插入时清空两个循环引用，并保存原 UUID 供 evaluation_run 后二阶段恢复。 */
    private List<SkillReferenceRestore> importSkillRows(StagedSnapshot staged) {
        List<SkillReferenceRestore> references = new ArrayList<>();
        readRows(staged, SnapshotTable.SKILL_VERSION, json -> {
            ObjectNode row = requireObjectRow(json);
            UUID id = requiredUuid(row, "id");
            UUID parent = optionalUuid(row, "parent_version_id");
            UUID evaluation = optionalUuid(row, "registered_evaluation_id");
            row.putNull("parent_version_id");
            row.putNull("registered_evaluation_id");
            repository.insertRow(SnapshotTable.SKILL_VERSION, writeJson(row));
            references.add(new SkillReferenceRestore(id, parent, evaluation));
        });
        return references;
    }

    /** 导入一张固定表；verification_task 唯一额外处理当前环境 upload_path。 */
    private void importTable(StagedSnapshot staged, SnapshotTable table) {
        readRows(staged, table, json -> {
            String imported = table == SnapshotTable.VERIFICATION_TASK ? normalizeImportedTaskRow(staged, json) : json;
            repository.insertRow(table, imported);
        });
    }

    /** 将快照相对 upload_path 验证到已暂存文件后，改写为当前 storageRoot 的绝对规范路径。 */
    private String normalizeImportedTaskRow(StagedSnapshot staged, String json) {
        ObjectNode row = requireObjectRow(json);
        String taskId = requiredText(row, "id");
        String relativeText = validateManifestFilePath(requiredText(row, "upload_path"));
        Path relative = Path.of(relativeText);
        if (!"uploads".equals(relative.getName(0).toString())
                || relative.getNameCount() != 3
                || !taskId.equals(relative.getName(1).toString())) {
            throw new ServiceException("DEMO_SNAPSHOT_UPLOAD_PATH_INVALID", "任务上传路径不符合固定 taskId 目录结构");
        }
        Path stagedUpload = requireWithinOperation(
                staged.expandedRoot(),
                staged.expandedRoot().resolve(FILES_ROOT).resolve(relative).normalize());
        if (!Files.isRegularFile(stagedUpload, LinkOption.NOFOLLOW_LINKS)) {
            throw new ServiceException("DEMO_SNAPSHOT_UPLOAD_PATH_INVALID", "任务上传文件未包含在快照文件清单中");
        }
        Path currentUpload =
                requireWithinStorageRoot(storageRoot.resolve(relative).normalize());
        row.put("upload_path", currentUpload.toString());
        return writeJson(row);
    }

    /** 按行读取已校验 JSONL；校验和导入使用同一暂存文件，不接受第二个输入来源。 */
    private void readRows(StagedSnapshot staged, SnapshotTable table, RowImporter importer) {
        Path path = staged.expandedRoot().resolve(tableEntryName(table));
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                importer.accept(line);
            }
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_IMPORT_FAILED", "快照表 JSONL 读取失败");
        }
    }

    /**
     * 把同一文件系统内的三个暂存目录逐个原子移动到空白正式路径。
     *
     * <p>跨三个目录不存在单一文件系统事务，因此任何中途异常由外层统一删除已移动目录并重建空白边界。
     */
    private void installManagedDirectories(StagedSnapshot staged) {
        try {
            for (String directoryName : MANAGED_DIRECTORIES) {
                Path source = requireWithinOperation(
                        staged.operationRoot(),
                        staged.expandedRoot().resolve(FILES_ROOT).resolve(directoryName));
                Files.createDirectories(source);
                Path target = requireWithinStorageRoot(storageRoot.resolve(directoryName));
                if (!isBlankDirectory(target)) {
                    throw new ServiceException("DEMO_STATE_NOT_BLANK", "正式运行目录在导入期间变为非空");
                }
                deleteTree(target);
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    throw new ServiceException("DEMO_SNAPSHOT_STORAGE_SWAP_FAILED", "当前文件系统不支持快照目录原子交换");
                }
                Files.writeString(target.resolve(GIT_KEEP), "", StandardCharsets.UTF_8);
            }
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_STORAGE_SWAP_FAILED", "快照运行目录交换失败");
        }
    }

    /** 失败时先恢复文件空白边界，再以独立事务清理固定七表；补偿异常作为 suppressed 保留。 */
    private void compensateFailedImport(boolean fileInstallStarted, RuntimeException original) {
        try {
            if (fileInstallStarted) {
                restoreBlankManagedDirectories();
            } else {
                ensureBlankManagedDirectoriesExist();
            }
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
        try {
            cleanupTransaction.executeWithoutResult(status -> repository.clearAll());
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    /** 删除本次可能已移动的正式目录并重建 .gitkeep，恢复导入前空白状态。 */
    private void restoreBlankManagedDirectories() {
        try {
            for (String directoryName : MANAGED_DIRECTORIES) {
                Path target = requireWithinStorageRoot(storageRoot.resolve(directoryName));
                deleteTree(target);
                Files.createDirectories(target);
                Files.writeString(target.resolve(GIT_KEEP), "", StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_ROLLBACK_FAILED", "快照失败后运行目录无法恢复为空白状态");
        }
    }

    /** 数据库在目录交换前失败时只补建原本允许缺失的空目录，不删除可能并发出现的内容。 */
    private void ensureBlankManagedDirectoriesExist() {
        try {
            for (String directoryName : MANAGED_DIRECTORIES) {
                Path target = requireWithinStorageRoot(storageRoot.resolve(directoryName));
                Files.createDirectories(target);
                if (isBlankDirectory(target)) {
                    Files.writeString(target.resolve(GIT_KEEP), "", StandardCharsets.UTF_8);
                }
            }
        } catch (IOException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_ROLLBACK_FAILED", "快照失败后运行目录无法恢复为空白状态");
        }
    }

    /** 只有不存在或仅包含普通 .gitkeep 文件的正式目录可作为原子移动目标。 */
    private boolean isBlankDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return true;
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            return false;
        }
        try (Stream<Path> children = Files.list(directory)) {
            return children.allMatch(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(path)
                    && GIT_KEEP.equals(path.getFileName().toString()));
        }
    }

    /** 解析必须为 object 的 JSON 行；所有表的原值仍由 JSON 文本交给 PostgreSQL 恢复。 */
    private ObjectNode requireObjectRow(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                throw new ServiceException("DEMO_SNAPSHOT_JSONL_INVALID", "快照表 JSONL 行不是 JSON object");
            }
            return (ObjectNode) node;
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_JSONL_INVALID", "快照表 JSONL 无法解析");
        }
    }

    /** 读取导入规则要求的非空文本字段。 */
    private static String requiredText(ObjectNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ServiceException("DEMO_SNAPSHOT_JSONL_INVALID", "快照表缺少必填字段：" + field);
        }
        return value.asText();
    }

    /** 读取必填 UUID；非法格式统一作为快照行错误，不向客户端暴露解析堆栈。 */
    private static UUID requiredUuid(ObjectNode row, String field) {
        try {
            return UUID.fromString(requiredText(row, field));
        } catch (IllegalArgumentException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_JSONL_INVALID", "快照表 UUID 字段无效：" + field);
        }
    }

    /** 读取可空 UUID；JSON null 与缺失均保留为空。 */
    private static UUID optionalUuid(ObjectNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new ServiceException("DEMO_SNAPSHOT_JSONL_INVALID", "快照表 UUID 字段无效：" + field);
        }
        try {
            return UUID.fromString(value.asText());
        } catch (IllegalArgumentException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_JSONL_INVALID", "快照表 UUID 字段无效：" + field);
        }
    }

    /** 序列化规范化后的个别行；除指定路径/引用列外不重建业务字段。 */
    private String writeJson(ObjectNode row) {
        try {
            return objectMapper.writeValueAsString(row);
        } catch (IOException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_JSONL_INVALID", "快照表 JSON 行无法序列化");
        }
    }

    /** 校验路径规范化后仍处于当前 storageRoot。 */
    private Path requireWithinStorageRoot(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(storageRoot)) {
            throw illegalPath();
        }
        return normalized;
    }

    /** 校验暂存路径始终处于本次 operationId 根，不能借路径文本访问其他导入现场。 */
    private static Path requireWithinOperation(Path operationRoot, Path candidate) {
        Path normalizedRoot = operationRoot.toAbsolutePath().normalize();
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) {
            throw illegalPath();
        }
        return normalized;
    }

    /** 返回不会包含调用方输入的本次导入根。 */
    private Path operationRoot(UUID operationId) {
        return requireWithinStorageRoot(storageRoot.resolve(IMPORT_ROOT).resolve(operationId.toString()));
    }

    /** 只忽略 .gitkeep 与任意层级 .demo-* 受控暂存物。 */
    private static boolean containsIgnoredSegment(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if (GIT_KEEP.equals(name) || name.startsWith(".demo-")) {
                return true;
            }
        }
        return false;
    }

    /** 固定表 entry 名称，调用方无法传入表名。 */
    private static String tableEntryName(SnapshotTable table) {
        return TABLES_ROOT + "/" + table.tableName() + ".jsonl";
    }

    /** 用 ZIP 规范的正斜杠表示跨平台相对路径。 */
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

    /** 写一个已在内存中的小型 manifest entry。 */
    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            input.transferTo(zip);
        }
        zip.closeEntry();
    }

    /** 计算磁盘暂存文件的 SHA-256。 */
    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    /** JDK 必须提供 SHA-256；缺失属于无法继续启动的运行环境错误。 */
    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    /** 输出 manifest 要求的 64 位小写十六进制。 */
    private static String hex(byte[] digest) {
        return java.util.HexFormat.of().formatHex(digest);
    }

    /** manifest 摘要只接受精确 64 位小写十六进制，避免多种文本表示造成歧义。 */
    private static boolean validSha(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }

    /** 创建隔离于调用方事务的模板。 */
    private static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /** 递归删除明确验证过的具体操作/受管目录；调用方不得传入宽泛根。 */
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new DeleteTreeException(exception);
                }
            });
        } catch (DeleteTreeException exception) {
            throw exception.getCause();
        }
    }

    /** 暂存清理失败不改变已确定的导入结果；遗留目录仍被 .demo-* 规则排除在后续快照外。 */
    private static void deleteTreeQuietly(Path root) {
        try {
            deleteTree(root);
        } catch (IOException exception) {
            // 此处不能把成功导入改报失败；固定隐藏根为后续人工清理保留明确边界。
        }
    }

    /** 生成统一的路径攻击业务异常，不回显攻击者提交的绝对路径。 */
    private static ServiceException illegalPath() {
        return new ServiceException("DEMO_SNAPSHOT_PATH_INVALID", "快照包含非法文件路径");
    }

    /** 已完整校验的本次暂存根、展开根和 manifest；不向 HTTP 调用方暴露真实路径。 */
    record StagedSnapshot(Path operationRoot, Path expandedRoot, SnapshotManifest manifest) {}

    /** 记录规范化 entry 的磁盘位置与目录属性，供 manifest 精确比对。 */
    private record StagedEntry(Path path, boolean directory) {}

    /** Skill 二阶段引用回填所需的原始 UUID。 */
    private record SkillReferenceRestore(UUID id, UUID parentVersionId, UUID registeredEvaluationId) {}

    /** 导入单行的内部回调；业务异常保持 RuntimeException 语义触发事务回滚。 */
    @FunctionalInterface
    private interface RowImporter {
        void accept(String json);
    }

    /** 把 Stream lambda 中的受检删除异常恢复到显式 IOException 边界。 */
    private static final class DeleteTreeException extends RuntimeException {
        private DeleteTreeException(IOException cause) {
            super(cause);
        }

        @Override
        public synchronized IOException getCause() {
            return (IOException) super.getCause();
        }
    }
}
