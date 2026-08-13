package com.hsmap.factverification.demo;

import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.shared.ServiceException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
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

    /** 生产环境从工作台既有 storageRoot 取得唯一允许管理的目录根。 */
    @Autowired
    public ManagedStorageSwap(WorkbenchProperties properties) {
        this(properties.storageRoot());
    }

    /** 为文件系统单元测试提供隔离根；生产装配始终使用 WorkbenchProperties 构造器。 */
    public ManagedStorageSwap(Path storageRoot) {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
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
            Files.createDirectories(archiveRoot);
            for (String directoryName : MANAGED_DIRECTORIES) {
                Path source = requireWithinStorageRoot(storageRoot.resolve(directoryName));
                Path archive = requireWithinStorageRoot(archiveRoot.resolve(directoryName));
                Files.createDirectories(source);
                atomicMove(source, archive);
                movedDirectories.put(source, archive);
                createEmptyManagedDirectory(source);
            }
            return new PreparedStorageSwap(archiveRoot, Map.copyOf(movedDirectories));
        } catch (IOException exception) {
            restoreMovedDirectories(movedDirectories);
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
            for (Map.Entry<Path, Path> entry : prepared.movedDirectories().entrySet()) {
                deleteTree(requireWithinStorageRoot(entry.getKey()));
                atomicMove(requireWithinStorageRoot(entry.getValue()), requireWithinStorageRoot(entry.getKey()));
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

    private void restoreMovedDirectories(Map<Path, Path> movedDirectories) {
        try {
            for (Map.Entry<Path, Path> entry : movedDirectories.entrySet()) {
                deleteTree(requireWithinStorageRoot(entry.getKey()));
                atomicMove(requireWithinStorageRoot(entry.getValue()), requireWithinStorageRoot(entry.getKey()));
            }
        } catch (IOException restoreException) {
            // 首次移动失败后的恢复已经无法向调用方提供安全的“可继续”状态，因此保留现场并由原始异常统一转换为业务错误。
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
            try (Stream<Path> children = Files.list(directory)) {
                return children.allMatch(child -> Files.isRegularFile(child) && GIT_KEEP.equals(child.getFileName().toString()));
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

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw exception;
        }
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
}
