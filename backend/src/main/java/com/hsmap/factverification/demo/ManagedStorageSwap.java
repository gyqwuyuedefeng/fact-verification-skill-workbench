package com.hsmap.factverification.demo;

import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.shared.ServiceException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** 生产环境从工作台既有 storageRoot 取得唯一允许管理的目录根。 */
    @Autowired
    public ManagedStorageSwap(WorkbenchProperties properties) {
        this(properties.storageRoot());
    }

    /** 为文件系统单元测试提供隔离根；生产装配始终使用 WorkbenchProperties 构造器。 */
    public ManagedStorageSwap(Path storageRoot) {
        this(storageRoot, ManagedStorageSwap::atomicMove, ManagedStorageSwap::regularMove);
    }

    /** 包内测试可注入只在指定一次移动失败的替身，验证三目录交换的精确恢复语义。 */
    ManagedStorageSwap(Path storageRoot, AtomicMover mover) {
        this(storageRoot, mover, ManagedStorageSwap::regularMove);
    }

    /** 包内测试分别控制原子移动和 DrvFS 降级移动，生产装配始终使用两个固定 Files.move 实现。 */
    ManagedStorageSwap(Path storageRoot, AtomicMover atomicMover, AtomicMover regularMover) {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
        this.atomicMover = atomicMover;
        this.regularMover = regularMover;
    }

    /**
     * 原子地腾空三个受管目录，并返回可用于提交清理或异常恢复的交换凭据。
     *
     * <p>若任一移动失败，已移动目录立即恢复；不使用复制降级，以确保数据库尚未操作时文件边界仍是可逆的。
     */
    public PreparedStorageSwap prepare(UUID operationId) {
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
            return new PreparedStorageSwap(archiveRoot, Map.copyOf(movedDirectories));
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
            regularMover.move(safeSource, safeTarget);
        }
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

    /** 记录一次目录交换的暂存根及每个源目录对应的暂存位置，禁止调用方伪造路径。 */
    public record PreparedStorageSwap(Path archiveRoot, Map<Path, Path> movedDirectories) {}

    /** 原子移动是目录交换的唯一文件系统原语；测试替身不得改变路径白名单。 */
    @FunctionalInterface
    interface AtomicMover {
        void move(Path source, Path target) throws IOException;
    }
}
