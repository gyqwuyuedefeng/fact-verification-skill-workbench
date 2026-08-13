package com.hsmap.factverification.demo;

import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.shared.ServiceException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.FileStore;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 三个比赛运行目录的可恢复交换器。
 *
 * <p>它只管理 uploads、skill-snapshots 与 skill-runtime：先把完整目录原子移到 storageRoot/.demo-reset，再新建带 .gitkeep 的空目录。
 * 数据库事务失败时服务调用 restore 恢复原目录，提交成功后才删除暂存目录。
 */
@Component
public class ManagedStorageSwap {

    private static final List<String> MANAGED_DIRECTORIES =
            List.of("uploads", "skill-snapshots", "skill-runtime");
    private static final String GIT_KEEP = ".gitkeep";

    private final Path storageRoot;
    private final AtomicMover atomicMover;
    private final AtomicMover regularMover;
    private final FileStoreResolver fileStoreResolver;
    private final DemoAdminProperties.StorageSwapMode storageSwapMode;
    private final int maxEntryCount;
    private final long maxExpandedBytes;
    private final CopyVerifyFaults copyVerifyFaults;

    /** 生产环境从工作台既有 storageRoot 取得唯一允许管理的目录根。 */
    @Autowired
    public ManagedStorageSwap(WorkbenchProperties properties, DemoAdminProperties adminProperties) {
        this(
                properties.storageRoot(),
                ManagedStorageSwap::atomicMove,
                ManagedStorageSwap::regularMove,
                Files::getFileStore,
                adminProperties.storageSwapMode(),
                adminProperties.maxEntryCount(),
                adminProperties.maxExpandedBytes(),
                CopyVerifyFaults.NONE);
    }

    /** 为文件系统单元测试提供隔离根；生产装配始终使用 WorkbenchProperties 构造器。 */
    public ManagedStorageSwap(Path storageRoot) {
        this(
                storageRoot,
                ManagedStorageSwap::atomicMove,
                ManagedStorageSwap::regularMove,
                Files::getFileStore,
                DemoAdminProperties.StorageSwapMode.MOVE,
                2_000,
                500L * 1024 * 1024,
                CopyVerifyFaults.NONE);
    }

    /** 包内测试可注入只在指定一次移动失败的替身，验证三目录交换的精确恢复语义。 */
    ManagedStorageSwap(Path storageRoot, AtomicMover mover) {
        this(
                storageRoot,
                mover,
                ManagedStorageSwap::regularMove,
                Files::getFileStore,
                DemoAdminProperties.StorageSwapMode.MOVE,
                2_000,
                500L * 1024 * 1024,
                CopyVerifyFaults.NONE);
    }

    /** 包内测试分别控制原子移动和 DrvFS 降级移动，生产装配始终使用两个固定 Files.move 实现。 */
    ManagedStorageSwap(Path storageRoot, AtomicMover atomicMover, AtomicMover regularMover) {
        this(
                storageRoot,
                atomicMover,
                regularMover,
                Files::getFileStore,
                DemoAdminProperties.StorageSwapMode.MOVE,
                2_000,
                500L * 1024 * 1024,
                CopyVerifyFaults.NONE);
    }

    /** 包内测试可注入 FileStore 解析器，证明降级移动绝不跨文件系统。 */
    ManagedStorageSwap(
            Path storageRoot,
            AtomicMover atomicMover,
            AtomicMover regularMover,
            FileStoreResolver fileStoreResolver) {
        this(
                storageRoot,
                atomicMover,
                regularMover,
                fileStoreResolver,
                DemoAdminProperties.StorageSwapMode.MOVE,
                2_000,
                500L * 1024 * 1024,
                CopyVerifyFaults.NONE);
    }

    /** COPY_VERIFY 聚焦测试构造器；生产模式只能来自显式配置绑定。 */
    ManagedStorageSwap(
            Path storageRoot,
            DemoAdminProperties.StorageSwapMode storageSwapMode,
            int maxEntryCount,
            long maxExpandedBytes,
            CopyVerifyFaults copyVerifyFaults) {
        this(
                storageRoot,
                ManagedStorageSwap::atomicMove,
                ManagedStorageSwap::regularMove,
                Files::getFileStore,
                storageSwapMode,
                maxEntryCount,
                maxExpandedBytes,
                copyVerifyFaults);
    }

    /** 汇总生产与测试依赖；COPY_VERIFY 不调用 mover，MOVE 不调用故障注入点。 */
    private ManagedStorageSwap(
            Path storageRoot,
            AtomicMover atomicMover,
            AtomicMover regularMover,
            FileStoreResolver fileStoreResolver,
            DemoAdminProperties.StorageSwapMode storageSwapMode,
            int maxEntryCount,
            long maxExpandedBytes,
            CopyVerifyFaults copyVerifyFaults) {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
        this.atomicMover = atomicMover;
        this.regularMover = regularMover;
        this.fileStoreResolver = fileStoreResolver;
        this.storageSwapMode = storageSwapMode;
        this.maxEntryCount = maxEntryCount;
        this.maxExpandedBytes = maxExpandedBytes;
        this.copyVerifyFaults = copyVerifyFaults;
    }

    /**
     * 原子地腾空三个受管目录，并返回可用于提交清理或异常恢复的交换凭据。
     *
     * <p>若任一移动失败，已移动目录立即恢复；不使用复制降级，以确保数据库尚未操作时文件边界仍是可逆的。
     */
    public PreparedStorageSwap prepare(UUID operationId) {
        if (storageSwapMode == DemoAdminProperties.StorageSwapMode.COPY_VERIFY) {
            return prepareCopyVerify(operationId);
        }
        return prepareMove(operationId);
    }

    /** 原 MOVE 策略保持独立，COPY_VERIFY 不会由异常回退到这里。 */
    private PreparedStorageSwap prepareMove(UUID operationId) {
        Path archiveRoot = requireWithinStorageRoot(storageRoot.resolve(".demo-reset").resolve(operationId.toString()));
        Map<Path, Path> movedDirectories = new LinkedHashMap<>();
        try {
            Files.createDirectories(storageRoot);
            if (Files.isSymbolicLink(storageRoot)
                    || !Files.isDirectory(storageRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("storageRoot 不是普通目录");
            }
            Path resetRoot = requireWithinStorageRoot(storageRoot.resolve(".demo-reset"));
            if (Files.exists(resetRoot, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(resetRoot)
                        || !Files.isDirectory(resetRoot, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("演示交换暂存根不是普通目录");
                }
            } else {
                Files.createDirectory(resetRoot);
            }
            Files.createDirectory(archiveRoot);
            for (String directoryName : MANAGED_DIRECTORIES) {
                Path source = requireWithinStorageRoot(storageRoot.resolve(directoryName));
                Path archive = requireWithinStorageRoot(archiveRoot.resolve(directoryName));
                if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(source)
                            || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("受管运行目录不是普通目录");
                    }
                    moveWithinStorageRoot(source, archive);
                    movedDirectories.put(source, archive);
                }
                createEmptyManagedDirectory(source);
            }
            return new PreparedStorageSwap(
                    archiveRoot, Map.copyOf(movedDirectories), Map.of(), Set.of(), DemoAdminProperties.StorageSwapMode.MOVE);
        } catch (IOException exception) {
            if (restoreMovedDirectories(movedDirectories)) {
                cleanupEmptyFailedOperation(archiveRoot);
            }
            throw new ServiceException("DEMO_STORAGE_SWAP_FAILED", "演示运行目录暂存失败");
        }
    }

    /** 数据库事务提交成功后删除暂存的旧运行数据，结束本次不可逆清空操作。 */
    public void commit(PreparedStorageSwap prepared) {
        try {
            deleteTree(requireWithinStorageRoot(prepared.archiveRoot()));
            Path resetRoot = prepared.archiveRoot().getParent();
            if (resetRoot != null && Files.isDirectory(resetRoot) && isDirectoryEmpty(resetRoot)) {
                Files.delete(resetRoot);
            }
        } catch (IOException exception) {
            throw new ServiceException("DEMO_STORAGE_SWAP_FAILED", "演示运行目录暂存清理失败");
        }
    }

    /** 数据库事务未提交时删除新建空目录，并把暂存中的原目录逐个原子恢复。 */
    public void restore(PreparedStorageSwap prepared) {
        if (prepared.storageSwapMode() == DemoAdminProperties.StorageSwapMode.COPY_VERIFY) {
            restoreCopyVerifyPublic(prepared);
            return;
        }
        try {
            for (String directoryName : MANAGED_DIRECTORIES) {
                Path target = requireWithinStorageRoot(storageRoot.resolve(directoryName));
                deleteTree(target);
                Path archive = prepared.movedDirectories().get(target);
                if (archive != null && Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
                    moveWithinStorageRoot(requireWithinStorageRoot(archive), target);
                }
            }
            deleteTree(requireWithinStorageRoot(prepared.archiveRoot()));
            Path resetRoot = prepared.archiveRoot().getParent();
            if (resetRoot != null && Files.isDirectory(resetRoot) && isDirectoryEmpty(resetRoot)) {
                Files.delete(resetRoot);
            }
        } catch (IOException exception) {
            throw new ServiceException("DEMO_STORAGE_SWAP_FAILED", "演示运行目录恢复失败");
        }
    }

    /**
     * 把已完整校验的三个暂存目录安装到 prepare 留出的空白目标。
     *
     * <p>每个目标先原子移动到本次 archiveRoot 下，而不是先删除；任一安装失败时，调用方可用同一个 prepared
     * 精确恢复原先“缺失或普通 .gitkeep 目录”的形态，且不会触碰本次操作之外的文件。
     */
    public void replaceWith(PreparedStorageSwap prepared, Map<String, Path> stagedDirectories) {
        if (prepared.storageSwapMode() == DemoAdminProperties.StorageSwapMode.COPY_VERIFY) {
            replaceWithCopyVerify(prepared, stagedDirectories);
            return;
        }
        Path blankRoot = requireWithinStorageRoot(prepared.archiveRoot().resolve("installed-blank"));
        try {
            Files.createDirectories(blankRoot);
            for (String directoryName : MANAGED_DIRECTORIES) {
                Path source = requireWithinStorageRoot(stagedDirectories.get(directoryName));
                Path target = requireWithinStorageRoot(storageRoot.resolve(directoryName));
                Path blankBackup = requireWithinStorageRoot(blankRoot.resolve(directoryName));
                if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(source)
                        || !isBlank(target)) {
                    throw new ServiceException("DEMO_STATE_NOT_BLANK", "正式运行目录在导入期间变为非空");
                }
                moveWithinStorageRoot(target, blankBackup);
                moveWithinStorageRoot(source, target);
                Files.writeString(requireWithinStorageRoot(target.resolve(GIT_KEEP)), "");
            }
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException | NullPointerException exception) {
            throw new ServiceException("DEMO_SNAPSHOT_STORAGE_SWAP_FAILED", "快照运行目录安装失败");
        }
    }

    /** 返回三个固定目录是否只包含 .gitkeep，供状态查询和快照导入前检查使用。 */
    public Map<String, Boolean> blankState() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (String directoryName : MANAGED_DIRECTORIES) {
            result.put(directoryName, isBlank(requireWithinStorageRoot(storageRoot.resolve(directoryName))));
        }
        return Map.copyOf(result);
    }

    /**
     * 验证候选路径在规范化后仍属于 storageRoot。
     *
     * <p>此方法公开仅供路径边界测试使用；业务代码只传入本类固定的目录名，不接受外部路径参数。
     */
    public Path requireWithinStorageRoot(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(storageRoot)) {
            throw new ServiceException("DEMO_STORAGE_PATH_INVALID", "演示运行目录路径越出 storageRoot");
        }
        return normalized;
    }

    /**
     * 显式 COPY_VERIFY：三目录全部复制并双向复验成功后，才允许删除任何正式源。
     *
     * <p>该模式只用于单实例 test 管理入口；共享管理写锁与七表锁由调用服务持有，防止应用内文件生产者交错。它不实现进程崩溃 journal，
     * 也不承诺抵御绕过应用锁直接改目录的外部进程。
     */
    private PreparedStorageSwap prepareCopyVerify(UUID operationId) {
        Path archiveRoot = requireWithinStorageRoot(storageRoot.resolve(".demo-reset").resolve(operationId.toString()));
        Path backupRoot = requireWithinStorageRoot(archiveRoot.resolve("backup"));
        Map<String, TreeManifest> backups = new LinkedHashMap<>();
        Set<String> modified = new LinkedHashSet<>();
        try {
            createCopyOperationRoot(archiveRoot, backupRoot);
            for (String directoryName : MANAGED_DIRECTORIES) {
                Path source = requireWithinStorageRoot(storageRoot.resolve(directoryName));
                TreeManifest original = scanTree(source);
                Path backup = requireWithinStorageRoot(backupRoot.resolve(directoryName));
                copyTree(source, backup, original, false);
                requireManifest(backup, original);
                copyVerifyFaults.at(CopyVerifyPoint.AFTER_BACKUP_DIRECTORY, source, backup);
                requireManifest(source, original);
                backups.put(directoryName, original);
            }
            for (String directoryName : MANAGED_DIRECTORIES) {
                Path source = requireWithinStorageRoot(storageRoot.resolve(directoryName));
                modified.add(directoryName);
                copyVerifyFaults.at(CopyVerifyPoint.BEFORE_DELETE_FORMAL, source, backupRoot.resolve(directoryName));
                deleteTree(source);
                copyVerifyFaults.at(CopyVerifyPoint.AFTER_DELETE_FORMAL, source, backupRoot.resolve(directoryName));
                copyVerifyFaults.at(CopyVerifyPoint.BEFORE_CREATE_EMPTY, source, backupRoot.resolve(directoryName));
                createEmptyManagedDirectory(source);
            }
            return new PreparedStorageSwap(
                    archiveRoot,
                    Map.of(),
                    Map.copyOf(backups),
                    Set.copyOf(modified),
                    DemoAdminProperties.StorageSwapMode.COPY_VERIFY);
        } catch (IOException | RuntimeException original) {
            if (!modified.isEmpty()) {
                try {
                    restoreCopyVerify(archiveRoot, backupRoot, backups, modified);
                } catch (IOException | RuntimeException recoveryFailure) {
                    ServiceException failure = new ServiceException(
                            "DEMO_STORAGE_COPY_VERIFY_RECOVERY_FAILED", "演示运行目录复制补偿恢复失败，已保留操作现场");
                    failure.addSuppressed(original);
                    failure.addSuppressed(recoveryFailure);
                    throw failure;
                }
            }
            deleteTreeQuietly(archiveRoot);
            cleanupResetRootQuietly(archiveRoot.getParent());
            throw new ServiceException("DEMO_STORAGE_SWAP_FAILED", "演示运行目录复制验证暂存失败");
        }
    }

    /** COPY_VERIFY import 先完整扫描三个 staged，再逐个复制到正式空白目录并复验。 */
    private void replaceWithCopyVerify(PreparedStorageSwap prepared, Map<String, Path> stagedDirectories) {
        Map<String, TreeManifest> stagedManifests = new LinkedHashMap<>();
        try {
            for (String directoryName : MANAGED_DIRECTORIES) {
                Path source = requireWithinStorageRoot(stagedDirectories.get(directoryName));
                stagedManifests.put(directoryName, scanTree(source));
            }
            for (String directoryName : MANAGED_DIRECTORIES) {
                Path source = requireWithinStorageRoot(stagedDirectories.get(directoryName));
                Path target = requireWithinStorageRoot(storageRoot.resolve(directoryName));
                if (!isBlank(target)) {
                    throw new ServiceException("DEMO_STATE_NOT_BLANK", "正式运行目录在导入期间变为非空");
                }
                deleteTree(target);
                TreeManifest manifest = stagedManifests.get(directoryName);
                copyTree(source, target, manifest, true);
                requireManifest(target, manifest);
                requireManifest(source, manifest);
                Files.writeString(
                        requireWithinStorageRoot(target.resolve(GIT_KEEP)),
                        "",
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new ServiceException("DEMO_SNAPSHOT_STORAGE_SWAP_FAILED", "快照运行目录复制验证安装失败");
        }
    }

    /** 事务失败时从 prepare 已验证的 backup 重建原始三目录，并在成功后清理 operation。 */
    private void restoreCopyVerifyPublic(PreparedStorageSwap prepared) {
        try {
            restoreCopyVerify(
                    prepared.archiveRoot(),
                    prepared.archiveRoot().resolve("backup"),
                    prepared.copyBackups(),
                    prepared.modifiedDirectories());
        } catch (IOException | RuntimeException exception) {
            throw new ServiceException("DEMO_STORAGE_COPY_VERIFY_RECOVERY_FAILED", "演示运行目录复制补偿恢复失败，已保留操作现场");
        }
    }

    /** 补偿只处理本次已进入删除阶段的目录；没有 verified backup 的目录绝不删除。 */
    private void restoreCopyVerify(
            Path archiveRoot,
            Path backupRoot,
            Map<String, TreeManifest> backups,
            Set<String> modifiedDirectories)
            throws IOException {
        List<String> modified = new ArrayList<>(modifiedDirectories);
        Collections.reverse(modified);
        for (String directoryName : modified) {
            TreeManifest manifest = backups.get(directoryName);
            if (manifest == null) {
                throw new IOException("缺少已验证备份清单");
            }
            Path target = requireWithinStorageRoot(storageRoot.resolve(directoryName));
            Path backup = requireWithinStorageRoot(backupRoot.resolve(directoryName));
            requireManifest(backup, manifest);
            copyVerifyFaults.at(CopyVerifyPoint.BEFORE_RESTORE, backup, target);
            deleteTree(target);
            copyTree(backup, target, manifest, true);
            requireManifest(target, manifest);
        }
        deleteTree(requireWithinStorageRoot(archiveRoot));
        cleanupResetRootQuietly(archiveRoot.getParent());
    }

    /** operation 与 backup 均要求 CREATE_NEW 语义，任何预存现场都拒绝覆盖。 */
    private void createCopyOperationRoot(Path archiveRoot, Path backupRoot) throws IOException {
        Files.createDirectories(storageRoot);
        Path resetRoot = requireWithinStorageRoot(storageRoot.resolve(".demo-reset"));
        if (Files.notExists(resetRoot, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(resetRoot);
        } else if (Files.isSymbolicLink(resetRoot)
                || !Files.isDirectory(resetRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("复制验证暂存根不是普通目录");
        }
        Files.createDirectory(archiveRoot);
        Files.createDirectory(backupRoot);
    }

    /** 按稳定相对路径、节点类型、文件 size 与 SHA-256 扫描一棵受限目录树。 */
    private TreeManifest scanTree(Path root) throws IOException {
        Path safeRoot = requireWithinStorageRoot(root);
        if (Files.notExists(safeRoot, LinkOption.NOFOLLOW_LINKS)) {
            return new TreeManifest(false, Map.of());
        }
        if (Files.isSymbolicLink(safeRoot)
                || !Files.isDirectory(safeRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("受管树根不是普通目录");
        }
        Map<String, TreeEntry> entries = new LinkedHashMap<>();
        long[] bytes = {0L};
        try (Stream<Path> paths = Files.walk(safeRoot)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(safeRoot)) {
                    continue;
                }
                Path relative = safeRoot.relativize(path);
                String key = archivePath(relative);
                if (entries.size() >= maxEntryCount) {
                    throw new IOException("受管树 entry 数量超过限制");
                }
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("受管树包含符号链接");
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    entries.put(key, new TreeEntry(TreeEntryType.DIRECTORY, 0L, ""));
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    long size = Files.size(path);
                    bytes[0] += size;
                    if (bytes[0] > maxExpandedBytes) {
                        throw new IOException("受管树累计字节超过限制");
                    }
                    entries.put(key, new TreeEntry(TreeEntryType.FILE, size, sha256(path)));
                } else {
                    throw new IOException("受管树包含特殊文件");
                }
            }
        }
        // 复制必须保持父目录先于子节点的稳定遍历顺序；Map.copyOf 不承诺迭代顺序。
        return new TreeManifest(true, Collections.unmodifiableMap(new LinkedHashMap<>(entries)));
    }

    /** 只依据已扫描 manifest CREATE_NEW 复制，避免遍历时接受后来出现的额外节点。 */
    private void copyTree(Path source, Path target, TreeManifest manifest, boolean formalCopy) throws IOException {
        if (!manifest.rootPresent()) {
            return;
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("复制目标已存在");
        }
        Files.createDirectory(target);
        for (Map.Entry<String, TreeEntry> item : manifest.entries().entrySet()) {
            Path relative = Path.of(item.getKey());
            Path from = requireWithinStorageRoot(source.resolve(relative));
            Path to = requireWithinStorageRoot(target.resolve(relative));
            TreeEntry entry = item.getValue();
            if (entry.type() == TreeEntryType.DIRECTORY) {
                Files.createDirectory(to);
            } else {
                if (Files.isSymbolicLink(from)
                        || !Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("复制源在执行前不再是普通文件");
                }
                copyVerifyFaults.at(CopyVerifyPoint.BEFORE_COPY_FILE, from, to);
                Files.copy(from, to);
                copyVerifyFaults.at(
                        formalCopy ? CopyVerifyPoint.AFTER_FORMAL_COPY_FILE : CopyVerifyPoint.AFTER_COPY_FILE,
                        from,
                        to);
            }
        }
    }

    /** 重扫必须与原清单逐项相等，短写、篡改、缺失或多余节点都会失败。 */
    private void requireManifest(Path root, TreeManifest expected) throws IOException {
        if (!scanTree(root).equals(expected)) {
            throw new IOException("复制验证清单不一致");
        }
    }

    /** 失败前的空 operation 可静默清理；补偿失败路径不会调用此方法。 */
    private static void deleteTreeQuietly(Path root) {
        try {
            deleteTree(root);
        } catch (IOException ignored) {
            // 失败现场仅在尚未删除正式源时可留存，不覆盖主要业务异常。
        }
    }

    /** operation 清空后只删除空 .demo-reset 根，绝不递归触碰其他 operation。 */
    private static void cleanupResetRootQuietly(Path resetRoot) {
        try {
            if (resetRoot != null
                    && Files.isDirectory(resetRoot, LinkOption.NOFOLLOW_LINKS)
                    && isDirectoryEmpty(resetRoot)) {
                Files.delete(resetRoot);
            }
        } catch (IOException ignored) {
            // 空暂存根清理失败不扩大删除范围。
        }
    }

    /** ZIP/manifest 使用同样的正斜杠稳定相对路径。 */
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

    /** 文件摘要只覆盖真实字节，不依赖 DrvFS 元数据。 */
    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private boolean restoreMovedDirectories(Map<Path, Path> movedDirectories) {
        try {
            List<Map.Entry<Path, Path>> moved = new java.util.ArrayList<>(movedDirectories.entrySet());
            java.util.Collections.reverse(moved);
            for (Map.Entry<Path, Path> entry : moved) {
                Path target = requireWithinStorageRoot(entry.getKey());
                deleteTree(target);
                Path archive = entry.getValue();
                if (archive != null && Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
                    moveWithinStorageRoot(requireWithinStorageRoot(archive), target);
                }
            }
            return true;
        } catch (IOException restoreException) {
            // 首次移动失败后的恢复已经无法向调用方提供安全的“可继续”状态，因此保留现场并由原始异常统一转换为业务错误。
            return false;
        }
    }

    /** prepare 失败且全部已移动目录都恢复后，只清理本次确认为空的 operation，不遍历或删除正式目录。 */
    private void cleanupEmptyFailedOperation(Path archiveRoot) {
        try {
            if (Files.isDirectory(archiveRoot, LinkOption.NOFOLLOW_LINKS) && isDirectoryEmpty(archiveRoot)) {
                Files.delete(archiveRoot);
            }
            Path resetRoot = archiveRoot.getParent();
            if (resetRoot != null
                    && Files.isDirectory(resetRoot, LinkOption.NOFOLLOW_LINKS)
                    && isDirectoryEmpty(resetRoot)) {
                Files.delete(resetRoot);
            }
        } catch (IOException cleanupException) {
            // operation 仅为空目录；清理失败时保留现场，不得为清理它扩大到正式目录或覆盖原始移动异常。
        }
    }

    private void createEmptyManagedDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(requireWithinStorageRoot(directory.resolve(GIT_KEEP)), "");
    }

    private boolean isBlank(Path directory) {
        try {
            if (!Files.exists(directory)) {
                return true;
            }
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            try (Stream<Path> children = Files.list(directory)) {
                return children.allMatch(child -> Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)
                        && GIT_KEEP.equals(child.getFileName().toString()));
            }
        } catch (IOException exception) {
            throw new ServiceException("DEMO_STORAGE_SWAP_FAILED", "演示运行目录状态无法读取");
        }
    }

    private static boolean isDirectoryEmpty(Path directory) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            return children.findAny().isEmpty();
        }
    }

    /**
     * 在同一 storageRoot 内优先原子移动；仅 DrvFS 明确不支持 ATOMIC_MOVE 时降级为无覆盖普通 move。
     *
     * <p>调用仍位于共享管理写锁及数据库表锁包围的短事务内，源和目标均是固定受管路径；其他 IOException
     * 原样失败，不得泛化降级。两种移动前都要求目标在 NOFOLLOW 语义下不存在，禁止覆盖任何并发或异常现场。
     */
    private void moveWithinStorageRoot(Path source, Path target) throws IOException {
        Path safeSource = requireWithinStorageRoot(source);
        Path safeTarget = requireWithinStorageRoot(target);
        if (Files.exists(safeTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.FileAlreadyExistsException(safeTarget.toString());
        }
        try {
            atomicMover.move(safeSource, safeTarget);
        } catch (AtomicMoveNotSupportedException exception) {
            if (Files.exists(safeTarget, LinkOption.NOFOLLOW_LINKS)) {
                throw new java.nio.file.FileAlreadyExistsException(safeTarget.toString());
            }
            Path targetParent = nearestExistingDirectory(safeTarget.getParent());
            FileStore sourceStore = fileStoreResolver.fileStore(safeSource);
            FileStore targetStore = fileStoreResolver.fileStore(targetParent);
            if (!sourceStore.equals(targetStore)) {
                throw new IOException("源目录与目标父目录不属于同一 FileStore");
            }
            regularMover.move(safeSource, safeTarget);
        }
    }

    /** 从目标父目录向 storageRoot 回溯最近的普通现存目录，逐层拒绝链接和越界。 */
    private Path nearestExistingDirectory(Path candidate) throws IOException {
        Path current = requireWithinStorageRoot(candidate);
        while (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = current.getParent();
            if (parent == null || !parent.startsWith(storageRoot)) {
                throw new IOException("目标路径没有 storageRoot 内的现存父目录");
            }
            current = parent;
        }
        if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("目标最近现存父路径不是普通目录");
        }
        return current;
    }

    /** 生产原子移动不带 REPLACE；是否允许降级只由 moveWithinStorageRoot 判断。 */
    private static void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /** DrvFS 兼容移动同样不带 REPLACE，目标存在时由调用点和 Files.move 双重拒绝。 */
    private static void regularMove(Path source, Path target) throws IOException {
        Files.move(source, target);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new StorageDeleteException(exception);
                }
            });
        } catch (StorageDeleteException exception) {
            throw exception.getCause();
        }
    }

    /** 删除树时将 Lambda 中的受检 IOException 重新带回受检调用边界。 */
    private static final class StorageDeleteException extends RuntimeException {
        private StorageDeleteException(IOException cause) {
            super(cause);
        }

        @Override
        public synchronized IOException getCause() {
            return (IOException) super.getCause();
        }
    }

    /** 记录一次目录交换的 MOVE 或 COPY_VERIFY 恢复凭据，禁止调用方从 HTTP 输入伪造路径。 */
    public record PreparedStorageSwap(
            Path archiveRoot,
            Map<Path, Path> movedDirectories,
            Map<String, TreeManifest> copyBackups,
            Set<String> modifiedDirectories,
            DemoAdminProperties.StorageSwapMode storageSwapMode) {}

    /** COPY_VERIFY 清单的节点类型固定为普通目录或普通文件。 */
    public enum TreeEntryType {
        DIRECTORY,
        FILE
    }

    /** 单个节点只保存恢复所需的稳定类型、size 与 SHA。 */
    public record TreeEntry(TreeEntryType type, long size, String sha256) {}

    /** 根是否原本存在与稳定相对节点集合共同保留缺失/.gitkeep/嵌套三种形态。 */
    public record TreeManifest(boolean rootPresent, Map<String, TreeEntry> entries) {}

    /** 小型故障点仅供 COPY_VERIFY TDD 覆盖，不替代真实 Files API。 */
    enum CopyVerifyPoint {
        BEFORE_COPY_FILE,
        AFTER_COPY_FILE,
        AFTER_BACKUP_DIRECTORY,
        BEFORE_DELETE_FORMAL,
        AFTER_DELETE_FORMAL,
        BEFORE_CREATE_EMPTY,
        AFTER_FORMAL_COPY_FILE,
        BEFORE_RESTORE
    }

    /** 生产始终使用 NONE；测试可在精确阶段抛错或篡改隔离临时文件。 */
    @FunctionalInterface
    interface CopyVerifyFaults {
        CopyVerifyFaults NONE = (point, source, target) -> {};

        void at(CopyVerifyPoint point, Path source, Path target) throws IOException;
    }

    /** 原子移动是目录交换的唯一文件系统原语；测试替身不得改变路径白名单。 */
    @FunctionalInterface
    interface AtomicMover {
        void move(Path source, Path target) throws IOException;
    }

    /** FileStore 查询保留受检异常，任何取值失败都直接关闭普通 move 降级。 */
    @FunctionalInterface
    interface FileStoreResolver {
        FileStore fileStore(Path path) throws IOException;
    }
}
