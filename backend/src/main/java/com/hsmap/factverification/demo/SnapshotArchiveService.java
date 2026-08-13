package com.hsmap.factverification.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.shared.ServiceException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final String EXPORT_ROOT = ".demo-export";
    private static final int EOCD_MINIMUM_BYTES = 22;
    private static final int EOCD_MAXIMUM_TAIL_BYTES = EOCD_MINIMUM_BYTES + 65_535;
    private static final int CENTRAL_DIRECTORY_FIXED_HEADER_BYTES = 46;
    private static final int CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50;
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
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_JSONL_LINE_BYTES = 4 * 1024 * 1024;

    private final DemoStateRepository repository;
    private final DemoStateService stateService;
    private final Path storageRoot;
    private final long maxArchiveBytes;
    private final int maxEntryCount;
    private final long maxExpandedBytes;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate importTransaction;
    private final TransactionTemplate exportTransaction;
    private final DemoOperationCoordinator operationCoordinator;
    private final ManagedStorageSwap storageSwap;

    /**
     * 注入应用仓储、Task 4 状态门禁、既有 storageRoot、受控上限与数据库事务管理器。
     *
     * <p>导入使用 REQUIRES_NEW 事务；导出使用只读 REPEATABLE_READ 稳定快照。二者与 Task 4 reset
     * 共享单用途协调器，不依赖请求线程的外层事务。
     */
    @Autowired
    public SnapshotArchiveService(
            DemoStateRepository repository,
            DemoStateService stateService,
            WorkbenchProperties workbenchProperties,
            DemoAdminProperties adminProperties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            DemoOperationCoordinator operationCoordinator,
            ManagedStorageSwap storageSwap) {
        this.repository = repository;
        this.stateService = stateService;
        this.storageRoot = workbenchProperties.storageRoot().toAbsolutePath().normalize();
        this.maxArchiveBytes = adminProperties.maxArchiveBytes();
        this.maxEntryCount = adminProperties.maxEntryCount();
        this.maxExpandedBytes = adminProperties.maxExpandedBytes();
        this.objectMapper = objectMapper;
        this.importTransaction = requiresNew(transactionManager);
        this.exportTransaction = stableReadSnapshot(transactionManager);
        this.operationCoordinator = operationCoordinator;
        this.storageSwap = storageSwap;
    }

    /** 保留 Task 5 已有包内单元测试构造方式；生产 Spring 只选择带共享协调器的构造器。 */
    SnapshotArchiveService(
            DemoStateRepository repository,
            DemoStateService stateService,
            WorkbenchProperties workbenchProperties,
            DemoAdminProperties adminProperties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(
                repository,
                stateService,
                workbenchProperties,
                adminProperties,
                objectMapper,
                transactionManager,
                new DemoOperationCoordinator(),
                new ManagedStorageSwap(workbenchProperties.storageRoot()));
    }

    /** 并发聚焦测试可共享协调器观察锁周期，文件交换器仍限定在同一测试 storageRoot。 */
    SnapshotArchiveService(
            DemoStateRepository repository,
            DemoStateService stateService,
            WorkbenchProperties workbenchProperties,
            DemoAdminProperties adminProperties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            DemoOperationCoordinator operationCoordinator) {
        this(
                repository,
                stateService,
                workbenchProperties,
                adminProperties,
                objectMapper,
                transactionManager,
                operationCoordinator,
                new ManagedStorageSwap(workbenchProperties.storageRoot()));
    }

    /**
     * 向 HTTP 响应流直接写出 v1 ZIP，不在内存中聚合完整压缩包。
     *
     * <p>活动工作检查先于 ZIP header；数据库按行写 JSONL，文件按固定目录遍历并计算实际读取字节摘要，manifest 最后写入。
     */
    public void exportTo(OutputStream output) {
        UUID operationId = UUID.randomUUID();
        Path operationRoot = null;
        try {
            operationRoot = prepareOperationRoot(EXPORT_ROOT, operationId);
            Path archivePath = requireWithinOperation(operationRoot, operationRoot.resolve("snapshot.zip"));
            Path lockedOperationRoot = operationRoot;
            operationCoordinator.exclusively(() -> exportExclusively(archivePath, lockedOperationRoot));
            // 客户端下载速度不属于比赛状态替换边界；本地快照生成完毕后释放管理写锁，再执行网络复制。
            try (InputStream input = Files.newInputStream(archivePath)) {
                input.transferTo(output);
            }
            output.flush();
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_EXPORT_FAILED", "比赛状态快照导出失败");
        } finally {
            deleteTreeQuietly(operationRoot);
        }
    }

    /** 在 reset/import 不可交错的边界中，用单一数据库快照同时决定应导出的文件集。 */
    private void exportExclusively(Path archivePath, Path operationRoot) {
        try {
            Files.createDirectories(storageRoot);
            requireSafePhysicalParent(operationRoot, archivePath.getParent());
            try (OutputStream file = new LimitedOutputStream(
                            Files.newOutputStream(archivePath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                            maxArchiveBytes);
                    ZipOutputStream zip = new ZipOutputStream(file, StandardCharsets.UTF_8)) {
                SnapshotManifest[] manifest = new SnapshotManifest[1];
                exportTransaction.executeWithoutResult(status -> {
                    stateService.requireQuiescentForSnapshotExport();
                    try {
                        ExportSelection selection = new ExportSelection();
                        Map<String, SnapshotManifest.TableEntry> tables = exportTables(zip, selection);
                        List<SnapshotManifest.FileEntry> files = exportManagedFiles(zip, selection);
                        manifest[0] =
                                new SnapshotManifest(SnapshotManifest.FORMAT_VERSION, Instant.now(), tables, files);
                    } catch (IOException exception) {
                        throw new SnapshotIoException(exception);
                    }
                });
                writeEntry(zip, MANIFEST_ENTRY, objectMapper.writeValueAsBytes(manifest[0]));
                zip.finish();
            }
        } catch (ServiceException exception) {
            throw exception;
        } catch (SnapshotIoException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_EXPORT_FAILED", "比赛状态快照导出失败");
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
        importUntrustedOutsideLock(input);
    }

    /**
     * 导入由内置 fixture 服务在本机生成的 v1 快照。
     *
     * <p>该 adapter 只省略用户 ZIP 专用确认语；后续仍完整经过原始包落盘、EOCD/entry/manifest/JSONL/SHA 校验、管理写锁、固定七表锁、
     * 同一导入事务和目录交换。Task 6 不得绕过此入口直接写仓储或正式目录。
     */
    public void importValidatedBuiltin(InputStream input) {
        importUntrustedOutsideLock(input);
    }

    /** 原始网络流落盘与全部不可信 ZIP 校验在管理锁外完成，慢上传不能阻塞 reset 或普通文件生产。 */
    private void importUntrustedOutsideLock(InputStream input) {
        UUID operationId = UUID.randomUUID();
        Path operationRoot = null;
        try {
            operationRoot = prepareOperationRoot(IMPORT_ROOT, operationId);
            Path archivePath = copyArchive(input, operationRoot.resolve("archive.zip"));
            StagedSnapshot staged = validateStagedArchive(operationRoot, archivePath);
            preflightImportRows(staged);
            operationCoordinator.exclusively(() -> applyValidatedSnapshot(staged));
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_IMPORT_FAILED", "比赛状态快照导入失败");
        } finally {
            if (operationRoot != null) {
                deleteTreeQuietly(operationRoot);
            }
        }
    }

    /**
     * 把磁盘上的测试 ZIP 复制到独立操作目录并执行与 HTTP 导入完全相同的安全校验。
     *
     * <p>该包内入口只供聚焦安全测试使用，不写数据库、不交换正式目录；成功暂存由测试临时根随用例回收。
     */
    StagedSnapshot validateAndStage(Path zipPath) {
        UUID operationId = UUID.randomUUID();
        Path operationRoot = null;
        try {
            operationRoot = prepareOperationRoot(IMPORT_ROOT, operationId);
            try (InputStream input = Files.newInputStream(zipPath)) {
                Path archivePath = copyArchive(input, operationRoot.resolve("archive.zip"));
                return validateStagedArchive(operationRoot, archivePath);
            }
        } catch (ServiceException exception) {
            if (operationRoot != null) {
                deleteTreeQuietly(operationRoot);
            }
            throw exception;
        } catch (IOException exception) {
            if (operationRoot != null) {
                deleteTreeQuietly(operationRoot);
            }
            throw new ServiceException("DEMO_SNAPSHOT_IMPORT_FAILED", "比赛状态快照暂存失败");
        }
    }

    /** 按枚举顺序写七个 JSONL entry，并记录实际行数与完整文件摘要。 */
    private Map<String, SnapshotManifest.TableEntry> exportTables(ZipOutputStream zip, ExportSelection selection)
            throws IOException {
        Map<String, SnapshotManifest.TableEntry> result = new LinkedHashMap<>();
        for (SnapshotTable table : SnapshotTable.values()) {
            zip.putNextEntry(new ZipEntry(tableEntryName(table)));
            MessageDigest digest = sha256Digest();
            long[] rows = {0L};
            repository.exportRows(table, json -> {
                String normalized = json;
                if (table == SnapshotTable.VERIFICATION_TASK) {
                    normalized = normalizeExportTaskRow(json, selection);
                } else if (table == SnapshotTable.SKILL_VERSION) {
                    ObjectNode row = requireObjectRow(json);
                    selection.skillVersions().put(requiredUuid(row, "id"), requiredText(row, "status"));
                }
                byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_JSONL_LINE_BYTES) {
                    throw new ServiceException("DEMO_SNAPSHOT_JSONL_LINE_TOO_LARGE", "快照表 JSONL 单行大小超过限制");
                }
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

    /**
     * 只导出当前七表快照实际引用的上传文件与 Skill 版本目录。
     *
     * <p>这使活动检查后新出现的孤儿文件不会混入快照，同时避免对普通核验表加长时间排他锁。
     */
    private List<SnapshotManifest.FileEntry> exportManagedFiles(ZipOutputStream zip, ExportSelection selection)
            throws IOException {
        Set<Path> selected = new LinkedHashSet<>(selection.uploadFiles());
        for (Map.Entry<UUID, String> version : selection.skillVersions().entrySet()) {
            if ("DRAFT".equals(version.getValue())) {
                // 草稿的临时或失败冻结残留不属于数据库状态，必须从稳定快照中排除。
                continue;
            }
            if (!Set.of("CANDIDATE", "STABLE", "ARCHIVED").contains(version.getValue())) {
                throw new ServiceException("DEMO_SNAPSHOT_SKILL_STATUS_INVALID", "Skill 版本状态无法导出");
            }
            collectRequiredVersionFiles(selected, "skill-snapshots", version.getKey());
            collectRequiredVersionFiles(selected, "skill-runtime", version.getKey());
        }
        List<Path> files = new ArrayList<>(selected);
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

    /** 收集非 DRAFT Skill 的固定冻结目录；数据库已冻结而任一物理目录缺失时拒绝产生不可恢复快照。 */
    private void collectRequiredVersionFiles(Set<Path> selected, String managedRoot, UUID versionId)
            throws IOException {
        Path versionRoot = requireWithinStorageRoot(storageRoot.resolve(managedRoot).resolve(versionId.toString()));
        if (!Files.exists(versionRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new ServiceException("DEMO_SNAPSHOT_SKILL_FILES_MISSING", "冻结 Skill 目录缺失，不能导出快照");
        }
        if (Files.isSymbolicLink(versionRoot)
                || !Files.isDirectory(versionRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new ServiceException("DEMO_SNAPSHOT_FILE_INVALID", "Skill 版本目录不是受控普通目录");
        }
        requirePhysicalStoragePath(versionRoot);
        try (Stream<Path> paths = Files.walk(versionRoot)) {
            for (Path path : paths.sorted().toList()) {
                Path relative = storageRoot.relativize(path);
                if (containsIgnoredSegment(relative)) {
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    throw new ServiceException("DEMO_SNAPSHOT_FILE_INVALID", "受管目录包含不允许导出的符号链接");
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    selected.add(path);
                }
            }
        }
    }

    /** 把 verification_task.upload_path 变为环境无关的 uploads/{taskId}/{fileName}。 */
    private String normalizeExportTaskRow(String json, ExportSelection selection) {
        try {
            ObjectNode row = requireObjectRow(json);
            String taskId = requiredText(row, "id");
            Path upload = Path.of(requiredText(row, "upload_path")).toAbsolutePath().normalize();
            Path uploadsRoot = requireWithinStorageRoot(storageRoot.resolve("uploads"));
            Path expectedPlaceholder = uploadsRoot.resolve(taskId).resolve("pending-upload").normalize();
            if (isExactEmptyUploadSlot(row) && upload.equals(expectedPlaceholder)) {
                // create 只落库、不落空文件；若磁盘反而已有同名对象，就不再属于精确空槽，必须失败关闭。
                if (Files.exists(upload, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ServiceException("DEMO_SNAPSHOT_UPLOAD_PATH_INVALID", "空上传槽不应包含占位文件");
                }
                row.put("upload_path", "uploads/" + taskId + "/pending-upload");
                return writeJson(row);
            }
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
            requirePhysicalStoragePath(upload);
            row.put("upload_path", "uploads/" + taskId + "/" + relative.getFileName());
            selection.uploadFiles().add(upload);
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
        requireSafePhysicalParent(archivePath.getParent(), archivePath.getParent());
        try (OutputStream output = Files.newOutputStream(
                archivePath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
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
        createSafeDirectories(operationRoot, expandedRoot);
        Map<String, StagedEntry> entries = new LinkedHashMap<>();
        long expandedBytes = 0L;
        int entryCount = 0;
        EocdSummary eocd = preflightEocd(archivePath);
        try (ZipFile archive = ZipFile.builder()
                .setPath(archivePath)
                .setMaxNumberOfDisks(1)
                .get()) {
            var archiveEntries = archive.getEntriesInPhysicalOrder();
            while (archiveEntries.hasMoreElements()) {
                ZipArchiveEntry entry = archiveEntries.nextElement();
                entryCount++;
                if (entryCount > maxEntryCount) {
                    throw new ServiceException("DEMO_SNAPSHOT_ENTRY_LIMIT_EXCEEDED", "快照文件数量超过限制");
                }
                if (entry.isUnixSymlink()) {
                    throw new ServiceException("DEMO_SNAPSHOT_SYMLINK_ENTRY", "快照不允许包含符号链接 entry");
                }
                if (!archive.canReadEntryData(entry)) {
                    throw new ServiceException("DEMO_SNAPSHOT_ZIP_FEATURE_UNSUPPORTED", "快照包含不支持的 ZIP 特性");
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
                    createSafeDirectories(operationRoot, target);
                } else {
                    createSafeDirectories(operationRoot, target.getParent());
                    try (InputStream input = archive.getInputStream(entry);
                            OutputStream output = Files.newOutputStream(
                                    target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
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
            }
        }
        if (entryCount != eocd.totalEntries()) {
            throw new ServiceException("DEMO_SNAPSHOT_ZIP_INDEX_INVALID", "快照中央目录条目数量不一致");
        }
        SnapshotManifest manifest = validateManifest(expandedRoot, entries);
        return new StagedSnapshot(operationRoot, expandedRoot, manifest);
    }

    /**
     * 在 Commons Compress 建立完整中央目录索引前，仅解析文件尾 EOCD 的固定字段。
     *
     * <p>200 MiB/2000 entry 的比赛快照不需要 ZIP64 或分卷；先用最多 65,557 字节尾部窗口拒绝声明过多条目的小型恶意 ZIP，
     * 避免成熟 ZIP 解析器尚未获得控制权前就为数十万中央目录记录分配对象。随后以单个 46 字节缓冲扫描 CEN 固定头和三个长度字段，
     * 不读取文件名、不构造 entry 对象；entry 语义、Unix symlink 和压缩特性仍完全交由 Commons Compress。
     */
    private EocdSummary preflightEocd(Path archivePath) throws IOException {
        try (FileChannel channel = FileChannel.open(archivePath, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < EOCD_MINIMUM_BYTES) {
                throw invalidZipIndex();
            }
            int tailLength = (int) Math.min(fileSize, EOCD_MAXIMUM_TAIL_BYTES);
            ByteBuffer tail = ByteBuffer.allocate(tailLength).order(ByteOrder.LITTLE_ENDIAN);
            long tailStart = fileSize - tailLength;
            while (tail.hasRemaining()) {
                if (channel.read(tail, tailStart + tail.position()) < 0) {
                    throw invalidZipIndex();
                }
            }
            byte[] bytes = tail.array();
            int eocdOffset = -1;
            for (int index = bytes.length - EOCD_MINIMUM_BYTES; index >= 0; index--) {
                if ((bytes[index] & 0xff) == 0x50
                        && (bytes[index + 1] & 0xff) == 0x4b
                        && (bytes[index + 2] & 0xff) == 0x05
                        && (bytes[index + 3] & 0xff) == 0x06) {
                    int commentLength = unsignedShort(bytes, index + 20);
                    if (index + EOCD_MINIMUM_BYTES + commentLength == bytes.length) {
                        eocdOffset = index;
                        break;
                    }
                }
            }
            if (eocdOffset < 0) {
                throw invalidZipIndex();
            }
            int diskNumber = unsignedShort(bytes, eocdOffset + 4);
            int centralDisk = unsignedShort(bytes, eocdOffset + 6);
            int entriesOnDisk = unsignedShort(bytes, eocdOffset + 8);
            int totalEntries = unsignedShort(bytes, eocdOffset + 10);
            long centralSize = unsignedInt(bytes, eocdOffset + 12);
            long centralOffset = unsignedInt(bytes, eocdOffset + 16);
            if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk != totalEntries) {
                throw new ServiceException("DEMO_SNAPSHOT_ZIP_MULTIDISK_UNSUPPORTED", "快照不支持分卷 ZIP");
            }
            if (totalEntries == 0xffff || centralSize == 0xffff_ffffL || centralOffset == 0xffff_ffffL) {
                throw new ServiceException("DEMO_SNAPSHOT_ZIP64_UNSUPPORTED", "比赛快照不支持 ZIP64");
            }
            if (totalEntries > maxEntryCount) {
                throw new ServiceException("DEMO_SNAPSHOT_ENTRY_LIMIT_EXCEEDED", "快照文件数量超过限制");
            }
            long absoluteEocd = tailStart + eocdOffset;
            if (centralOffset > absoluteEocd
                    || centralSize != absoluteEocd - centralOffset) {
                throw invalidZipIndex();
            }
            scanCentralDirectoryBeforeCommons(channel, centralOffset, absoluteEocd, totalEntries);
            return new EocdSummary(totalEntries);
        }
    }

    /**
     * 用固定小缓冲验证 EOCD 指定范围内的每个 CEN 记录，且在第 2,001 项进入对象解析前立即拒绝。
     *
     * <p>这里只读取 CEN 的固定 46 字节头，并按 little-endian 的 name/extra/comment 长度安全推进 long cursor；
     * 不读取变长内容、不创建名称字符串或 ZipArchiveEntry。中央目录数字签名、SFX offset 修正及其他额外结构在 MVP 中 fail closed。
     */
    private void scanCentralDirectoryBeforeCommons(
            FileChannel channel, long centralOffset, long absoluteEocd, int declaredEntries) throws IOException {
        ByteBuffer fixedHeader = ByteBuffer.allocate(CENTRAL_DIRECTORY_FIXED_HEADER_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        long cursor = centralOffset;
        int actualEntries = 0;
        while (cursor < absoluteEocd) {
            if (actualEntries >= maxEntryCount) {
                throw new ServiceException("DEMO_SNAPSHOT_ENTRY_LIMIT_EXCEEDED", "快照文件数量超过限制");
            }
            long remaining = absoluteEocd - cursor;
            if (remaining < CENTRAL_DIRECTORY_FIXED_HEADER_BYTES) {
                throw invalidZipIndex();
            }
            fixedHeader.clear();
            readFixedHeader(channel, fixedHeader, cursor);
            fixedHeader.flip();
            if (fixedHeader.getInt(0) != CENTRAL_DIRECTORY_SIGNATURE) {
                throw invalidZipIndex();
            }
            int nameLength = Short.toUnsignedInt(fixedHeader.getShort(28));
            int extraLength = Short.toUnsignedInt(fixedHeader.getShort(30));
            int commentLength = Short.toUnsignedInt(fixedHeader.getShort(32));
            long recordLength = CENTRAL_DIRECTORY_FIXED_HEADER_BYTES
                    + (long) nameLength
                    + extraLength
                    + commentLength;
            if (recordLength > remaining) {
                throw invalidZipIndex();
            }
            cursor += recordLength;
            actualEntries++;
        }
        if (cursor != absoluteEocd || actualEntries != declaredEntries) {
            throw invalidZipIndex();
        }
    }

    /** positional read 必须填满同一个固定头；普通文件意外返回 0 或提前 EOF 均按截断归档拒绝。 */
    private static void readFixedHeader(FileChannel channel, ByteBuffer target, long offset) throws IOException {
        long position = offset;
        while (target.hasRemaining()) {
            int read = channel.read(target, position);
            if (read <= 0) {
                throw invalidZipIndex();
            }
            position += read;
        }
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, Short.BYTES)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .getShort()
                & 0xffff;
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset, Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt());
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
            manifest = objectMapper.readValue(readLimitedBytes(manifestEntry.path(), MAX_MANIFEST_BYTES), SnapshotManifest.class);
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
        if (rawPath == null
                || rawPath.startsWith("/")
                || rawPath.indexOf('\\') >= 0
                || WINDOWS_ABSOLUTE_PATH.matcher(rawPath).matches()
                || rawPath.startsWith(FILES_ROOT + "/")) {
            throw illegalPath();
        }
        String validated = validateEntryName(FILES_ROOT + "/" + rawPath, false);
        return validated.substring((FILES_ROOT + "/").length());
    }

    /** 逐行确认 JSONL 每行都是 JSON object，并返回精确非空行数。 */
    private long validateJsonLines(Path path) throws IOException {
        long[] rows = {0L};
        readUtf8Lines(path, MAX_JSONL_LINE_BYTES, line -> {
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
            rows[0]++;
        });
        return rows[0];
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

    /**
     * 在独立事务内按固定顺序插入行并安装文件。
     *
     * <p>数据库写入失败只依赖当前事务回滚，不另起 clearAll；文件仅记录并撤销本次已成功移入的目录。
     */
    private void applyValidatedSnapshot(StagedSnapshot staged) {
        AtomicReference<ManagedStorageSwap.PreparedStorageSwap> prepared = new AtomicReference<>();
        try {
            importTransaction.executeWithoutResult(status -> {
                // 固定七表排他锁必须是事务中的第一项数据库操作；随后重查空白，普通写入无法穿过检查混入。
                repository.lockAllTablesForStateReplacement();
                stateService.requireBlank();
                ManagedStorageSwap.PreparedStorageSwap swap = storageSwap.prepare(UUID.randomUUID());
                prepared.set(swap);
                List<SkillReferenceRestore> references = importSkillRows(staged);
                importTable(staged, SnapshotTable.EVALUATION_RUN);
                references.forEach(reference -> repository.restoreSkillReferences(
                        reference.id(), reference.parentVersionId(), reference.registeredEvaluationId()));
                for (SnapshotTable table : IMPORT_ORDER.subList(2, IMPORT_ORDER.size())) {
                    importTable(staged, table);
                }
                storageSwap.replaceWith(swap, stagedManagedDirectories(staged));
            });
        } catch (RuntimeException exception) {
            if (prepared.get() != null) {
                try {
                    storageSwap.restore(prepared.get());
                } catch (RuntimeException restoreException) {
                    exception.addSuppressed(restoreException);
                }
            }
            throw exception;
        }
        storageSwap.commit(prepared.get());
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
        boolean exactEmptySlot = isExactEmptyUploadSlot(row)
                && relative.equals(Path.of("uploads", taskId, "pending-upload"));
        if (exactEmptySlot && Files.exists(stagedUpload, LinkOption.NOFOLLOW_LINKS)) {
            throw new ServiceException("DEMO_SNAPSHOT_UPLOAD_PATH_INVALID", "空上传槽不应包含占位文件");
        }
        if (!exactEmptySlot && !Files.isRegularFile(stagedUpload, LinkOption.NOFOLLOW_LINKS)) {
            throw new ServiceException("DEMO_SNAPSHOT_UPLOAD_PATH_INVALID", "任务上传文件未包含在快照文件清单中");
        }
        Path currentUpload =
                requireWithinStorageRoot(storageRoot.resolve(relative).normalize());
        row.put("upload_path", currentUpload.toString());
        return writeJson(row);
    }

    /**
     * 逐字段识别 VerificationTaskService.create 尚未收到首次材料时写入的唯一空上传槽签名。
     *
     * <p>这不是“附件缺失可忽略”：状态、占位元数据及四个解析/证据字段必须全部精确匹配，路径还会由导出/导入调用点分别校验为
     * uploads/{taskId}/pending-upload；任何近似状态继续走真实文件存在性门禁。
     */
    private static boolean isExactEmptyUploadSlot(ObjectNode row) {
        return row.path("status").isTextual()
                && "UPLOADED".equals(row.path("status").textValue())
                && row.path("original_file_name").isTextual()
                && "pending-upload".equals(row.path("original_file_name").textValue())
                && row.path("media_type").isTextual()
                && "application/octet-stream".equals(row.path("media_type").textValue())
                && row.path("file_size").isIntegralNumber()
                && row.path("file_size").longValue() == 1L
                && row.path("file_hash").isTextual()
                && "0".repeat(64).equals(row.path("file_hash").textValue())
                && isExplicitNull(row, "parser_version")
                && isExplicitNull(row, "document_snapshot")
                && isExplicitNull(row, "document_snapshot_hash")
                && isExplicitNull(row, "evidence_snapshot_id");
    }

    /** 空槽签名要求字段真实存在且为 JSON null，缺字段不能被 path().isMissingNode() 冒充。 */
    private static boolean isExplicitNull(ObjectNode row, String field) {
        return row.has(field) && row.get(field).isNull();
    }

    /** 按行读取已校验 JSONL；校验和导入使用同一暂存文件，不接受第二个输入来源。 */
    private void readRows(StagedSnapshot staged, SnapshotTable table, RowImporter importer) {
        Path path = staged.expandedRoot().resolve(tableEntryName(table));
        try {
            readUtf8Lines(path, MAX_JSONL_LINE_BYTES, importer);
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_IMPORT_FAILED", "快照表 JSONL 读取失败");
        }
    }

    /** 将缺失的 files 根补成普通空目录，并只返回交换器认识的三个固定目录名。 */
    private Map<String, Path> stagedManagedDirectories(StagedSnapshot staged) {
        Map<String, Path> result = new LinkedHashMap<>();
        try {
            for (String directoryName : MANAGED_DIRECTORIES) {
                Path source = requireWithinOperation(
                        staged.operationRoot(),
                        staged.expandedRoot().resolve(FILES_ROOT).resolve(directoryName));
                createSafeDirectories(staged.operationRoot(), source);
                result.put(directoryName, source);
            }
            return Map.copyOf(result);
        } catch (IOException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_STORAGE_SWAP_FAILED", "快照运行目录安装准备失败");
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

    /**
     * 以有界字节缓冲逐行读取 UTF-8 JSONL，绝不在确认单行大小前调用 readLine。
     *
     * <p>包内可见的上限参数只供小数据测试触发同一生产分支；业务入口固定使用 4 MiB 常量。
     */
    static void readUtf8Lines(Path path, int maxLineBytes, RowImporter consumer) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(maxLineBytes, 8192));
            int value;
            while ((value = input.read()) != -1) {
                if (value == '\n') {
                    consumer.accept(decodeLine(line));
                    line.reset();
                    continue;
                }
                if (line.size() >= maxLineBytes) {
                    throw new ServiceException("DEMO_SNAPSHOT_JSONL_LINE_TOO_LARGE", "快照表 JSONL 单行大小超过限制");
                }
                line.write(value);
            }
            if (line.size() > 0) {
                consumer.accept(decodeLine(line));
            }
        }
    }

    /** 将已通过字节上限的单行解码，并兼容 ZIP 生成器使用的 CRLF。 */
    private static String decodeLine(ByteArrayOutputStream line) {
        byte[] bytes = line.toByteArray();
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    /** 有界读取小型 manifest；业务入口固定 1 MiB，测试可用小上限验证早停。 */
    static byte[] readLimitedBytes(Path path, int maxBytes) throws IOException {
        try (InputStream input = Files.newInputStream(path);
                ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192))) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer, 0, Math.min(buffer.length, maxBytes - total + 1))) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new ServiceException("DEMO_SNAPSHOT_MANIFEST_TOO_LARGE", "快照 manifest 大小超过限制");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
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

    /** 拒绝 storageRoot 与候选文件之间的任何 symlink，并再次比对最终物理位置。 */
    private void requirePhysicalStoragePath(Path candidate) throws IOException {
        Path normalized = requireWithinStorageRoot(candidate);
        Path current = storageRoot;
        if (Files.isSymbolicLink(current)) {
            throw new ServiceException("DEMO_SNAPSHOT_FILE_INVALID", "受管文件路径包含符号链接");
        }
        for (Path part : storageRoot.relativize(normalized)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new ServiceException("DEMO_SNAPSHOT_FILE_INVALID", "受管文件路径包含符号链接");
            }
        }
        if (!normalized.toRealPath().startsWith(storageRoot.toRealPath())) {
            throw new ServiceException("DEMO_SNAPSHOT_FILE_INVALID", "受管文件物理路径越出 storageRoot");
        }
    }

    /**
     * 逐层创建本次暂存目录，且对每个已有层级使用 NOFOLLOW_LINKS 拒绝符号链接。
     *
     * <p>每创建或复用一层都比对物理路径，确保 `.demo-import/operation/expanded` 不会通过预置中间路径逃离 storageRoot。
     */
    private void createSafeDirectories(Path trustedRoot, Path directory) throws IOException {
        Path root = trustedRoot.toAbsolutePath().normalize();
        Path target = requireWithinOperation(root, directory);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw invalidStagingPath();
        }
        Path physicalRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path current = root;
        Path relative = root.relativize(target);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw invalidStagingPath();
                }
            } else {
                try {
                    Files.createDirectory(current);
                } catch (java.nio.file.FileAlreadyExistsException exception) {
                    if (Files.isSymbolicLink(current)
                            || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                        throw invalidStagingPath();
                    }
                }
            }
            if (!current.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(physicalRoot)) {
                throw invalidStagingPath();
            }
        }
    }

    /** 创建不可预测 operationId 目录，并从 storageRoot 开始逐层验证物理位置。 */
    private Path prepareOperationRoot(String hiddenRoot, UUID operationId) throws IOException {
        if (Files.exists(storageRoot, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(storageRoot)
                    || !Files.isDirectory(storageRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw invalidStagingPath();
            }
        } else {
            Files.createDirectories(storageRoot);
        }
        Path root = operationRoot(hiddenRoot, operationId);
        createSafeDirectories(storageRoot, root);
        return root;
    }

    /** 确认已准备的目录仍是普通目录，用于创建 archive.zip 前再次收紧竞态窗口。 */
    private void requireSafePhysicalParent(Path trustedRoot, Path directory) throws IOException {
        createSafeDirectories(trustedRoot, directory);
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

    /** 返回不会包含调用方输入的本次导入或导出操作根。 */
    private Path operationRoot(String hiddenRoot, UUID operationId) {
        if (!IMPORT_ROOT.equals(hiddenRoot) && !EXPORT_ROOT.equals(hiddenRoot)) {
            throw new ServiceException("DEMO_SNAPSHOT_STAGING_PATH_INVALID", "快照暂存根不受支持");
        }
        return requireWithinStorageRoot(storageRoot.resolve(hiddenRoot).resolve(operationId.toString()));
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

    /** 七表导出固定使用独立的只读 REPEATABLE_READ，使每张表不会看到不同提交时点。 */
    private static TransactionTemplate stableReadSnapshot(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = requiresNew(transactionManager);
        template.setReadOnly(true);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
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
        if (root == null) {
            return;
        }
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

    /** 暂存中间路径的符号链接或物理越界使用独立错误码，不回显本机路径。 */
    private static ServiceException invalidStagingPath() {
        return new ServiceException("DEMO_SNAPSHOT_STAGING_PATH_INVALID", "快照包含非法暂存路径");
    }

    /** EOCD 汇总或中央目录范围异常时，统一拒绝进入 Commons Compress 索引阶段。 */
    private static ServiceException invalidZipIndex() {
        return new ServiceException("DEMO_SNAPSHOT_ZIP_INDEX_INVALID", "快照 ZIP 中央目录索引无效");
    }

    /** 已完整校验的本次暂存根、展开根和 manifest；不向 HTTP 调用方暴露真实路径。 */
    record StagedSnapshot(Path operationRoot, Path expandedRoot, SnapshotManifest manifest) {}

    /** 记录规范化 entry 的磁盘位置与目录属性，供 manifest 精确比对。 */
    private record StagedEntry(Path path, boolean directory) {}

    /** Skill 二阶段引用回填所需的原始 UUID。 */
    private record SkillReferenceRestore(UUID id, UUID parentVersionId, UUID registeredEvaluationId) {}

    /** 七表快照读取时同步收集实际文件引用，避免事后再查数据库产生跨视图。 */
    private record ExportSelection(Set<Path> uploadFiles, Map<UUID, String> skillVersions) {
        private ExportSelection() {
            this(new LinkedHashSet<>(), new LinkedHashMap<>());
        }
    }

    /** EOCD 预检只向成熟解析阶段传递预期条目总数，不信任 entry 名称或 size。 */
    private record EocdSummary(int totalEntries) {}

    /** 导入单行的内部回调；业务异常保持 RuntimeException 语义触发事务回滚。 */
    @FunctionalInterface
    interface RowImporter {
        void accept(String json);
    }

    /** 把事务回调内的受检 ZIP I/O 恢复到外层统一业务异常边界。 */
    private static final class SnapshotIoException extends RuntimeException {
        private SnapshotIoException(IOException cause) {
            super(cause);
        }
    }

    /** 对导出本地临时 ZIP 同样执行原始压缩包硬上限，避免慢客户端之外的磁盘耗尽。 */
    private static final class LimitedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final long maximumBytes;
        private long written;

        private LimitedOutputStream(OutputStream delegate, long maximumBytes) {
            this.delegate = delegate;
            this.maximumBytes = maximumBytes;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
            written++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureCapacity(length);
            delegate.write(bytes, offset, length);
            written += length;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private void ensureCapacity(int bytes) {
            if (bytes > maximumBytes - written) {
                throw new ServiceException("DEMO_SNAPSHOT_ARCHIVE_TOO_LARGE", "导出快照压缩包大小超过限制");
            }
        }
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
