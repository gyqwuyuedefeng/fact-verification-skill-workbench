package com.hsmap.factverification.demo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hsmap.factverification.shared.ServiceException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 被测试对象：ManagedStorageSwap 的固定受管目录边界。
 * 测试目的：确保目录交换只允许在 storageRoot 内操作，避免演示管理功能扩展为任意路径删除工具。
 * 覆盖范围：规范化后的路径边界异常。
 * 前置条件：storageRoot 使用一个不含三个标准运行子目录的普通相对路径，模拟错误配置输入。
 */
class ManagedStorageSwapTest {

    @TempDir
    Path storageRoot;

    /**
     * 测试场景：配置的 storageRoot 被规范化后指向父目录之外的受管目标。
     * 前置条件：调用方显式传入越出 storageRoot 的候选路径。
     * 期望结果：拒绝操作并返回 DEMO_STORAGE_PATH_INVALID。
     * 断言重点：任何路径遍历都不能触发文件移动或删除。
     */
    @Test
    void rejectsManagedPathOutsideStorageRoot() {
        ManagedStorageSwap swap = new ManagedStorageSwap(Path.of("storage-root"));

        assertThatThrownBy(() -> swap.requireWithinStorageRoot(Path.of("storage-root/../outside")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("DEMO_STORAGE_PATH_INVALID");
    }

    /**
     * 测试场景：运行目录中存在名为 .gitkeep 的子目录，且其中包含运行产物。
     * 前置条件：只有普通 .gitkeep 文件可作为 Git 边界被忽略，目录不是可忽略文件。
     * 期望结果：uploads 被判定为非空。
     * 断言重点：攻击者或异常运行不能通过创建同名目录绕过快照导入和清空前的目录状态判断。
     */
    @Test
    void treatsGitkeepDirectoryAndItsContentsAsNonBlank() throws Exception {
        Path fakeGitkeepDirectory = storageRoot.resolve("uploads/.gitkeep");
        Files.createDirectories(fakeGitkeepDirectory);
        Files.writeString(fakeGitkeepDirectory.resolve("runtime.txt"), "不可忽略的运行产物");
        ManagedStorageSwap swap = new ManagedStorageSwap(storageRoot);

        org.assertj.core.api.Assertions.assertThat(swap.blankState().get("uploads")).isFalse();
    }

    /**
     * 测试场景：运行目录中的 .gitkeep 是指向普通文件的符号链接。
     * 前置条件：只有不跟随符号链接的普通文件可被忽略；当前操作系统若不支持创建链接则明确跳过。
     * 期望结果：符号链接被判定为非空，不能借由链接逃避受管运行目录检查。
     * 断言重点：Files.isRegularFile 必须使用 NOFOLLOW_LINKS，目录、链接及其目标内容都不是 Git 边界文件。
     */
    @Test
    void treatsGitkeepSymlinkAsNonBlank() throws Exception {
        Path uploads = storageRoot.resolve("uploads");
        Files.createDirectories(uploads);
        Path target = storageRoot.resolve("linked-runtime.txt");
        Files.writeString(target, "链接目标不是 Git 边界文件");
        try {
            Files.createSymbolicLink(uploads.resolve(".gitkeep"), target);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            Assumptions.abort("当前文件系统不支持符号链接测试：" + exception.getClass().getSimpleName());
        }
        ManagedStorageSwap swap = new ManagedStorageSwap(storageRoot);

        org.assertj.core.api.Assertions.assertThat(Files.isRegularFile(uploads.resolve(".gitkeep"), LinkOption.NOFOLLOW_LINKS))
                .isFalse();
        org.assertj.core.api.Assertions.assertThat(swap.blankState().get("uploads")).isFalse();
    }

    /**
     * 测试场景：导入前 uploads 只有普通 .gitkeep，skill-snapshots 缺失，第二个快照目录安装移动失败。
     * 前置条件：使用可控原子移动替身，允许三个备份移动和第一个安装，仅在第二个安装失败。
     * 期望结果：调用方恢复后，uploads 精确回到 .gitkeep，原缺失目录仍缺失，不留下部分安装文件。
     * 断言重点：正式目标在安装前先备份，不能先 delete 导致原空白形态丢失。
     */
    @Test
    void restoresGitkeepAndMissingShapesWhenSnapshotInstallMoveFails() throws Exception {
        Files.createDirectories(storageRoot.resolve("uploads"));
        Files.writeString(storageRoot.resolve("uploads/.gitkeep"), "");
        Files.createDirectories(storageRoot.resolve("skill-runtime"));
        Files.writeString(storageRoot.resolve("skill-runtime/.gitkeep"), "");
        Path staged = storageRoot.resolve("staged");
        Map<String, Path> stagedDirectories = new LinkedHashMap<>();
        for (String directory : java.util.List.of("uploads", "skill-snapshots", "skill-runtime")) {
            Path source = staged.resolve(directory);
            Files.createDirectories(source);
            Files.writeString(source.resolve("snapshot.txt"), directory);
            stagedDirectories.put(directory, source);
        }
        AtomicInteger moves = new AtomicInteger();
        ManagedStorageSwap swap = new ManagedStorageSwap(storageRoot, (source, target) -> {
            int attempt = moves.incrementAndGet();
            if (attempt == 5) {
                throw new java.io.IOException("模拟非兼容性目录安装失败");
            }
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        });
        ManagedStorageSwap.PreparedStorageSwap prepared = swap.prepare(UUID.randomUUID());

        assertThatThrownBy(() -> swap.replaceWith(prepared, stagedDirectories))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("安装失败");
        swap.restore(prepared);

        org.assertj.core.api.Assertions.assertThat(storageRoot.resolve("uploads/.gitkeep"))
                .isRegularFile();
        org.assertj.core.api.Assertions.assertThat(storageRoot.resolve("uploads/snapshot.txt"))
                .doesNotExist();
        org.assertj.core.api.Assertions.assertThat(storageRoot.resolve("skill-snapshots"))
                .doesNotExist();
        org.assertj.core.api.Assertions.assertThat(storageRoot.resolve("skill-runtime/.gitkeep"))
                .isRegularFile();
    }

    /**
     * 测试场景：当前文件系统在第一个受管目录移动时就不支持 ATOMIC_MOVE。
     * 前置条件：三个原目录都含真实运行文件，移动替身在改变任何路径前抛出 AtomicMoveNotSupportedException。
     * 期望结果：prepare 失败但三个从未移动的原目录和文件全部保留。
     * 断言重点：补偿只能删除本次确实已移到 archive 的目标；空 movedDirectories 绝不能演变为清空全部原目录。
     */
    @Test
    void doesNotDeleteUntouchedDirectoriesWhenFirstAtomicMoveFails() throws Exception {
        for (String directory : java.util.List.of("uploads", "skill-snapshots", "skill-runtime")) {
            Path runtimeFile = storageRoot.resolve(directory).resolve("original.txt");
            Files.createDirectories(runtimeFile.getParent());
            Files.writeString(runtimeFile, directory);
        }
        ManagedStorageSwap swap = new ManagedStorageSwap(storageRoot, (source, target) -> {
            throw new java.io.IOException("模拟第一次移动在改变路径前失败");
        });

        assertThatThrownBy(() -> swap.prepare(UUID.randomUUID()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("暂存失败");

        for (String directory : java.util.List.of("uploads", "skill-snapshots", "skill-runtime")) {
            org.assertj.core.api.Assertions.assertThat(storageRoot.resolve(directory).resolve("original.txt"))
                    .hasContent(directory);
        }
        assertDemoResetHasNoOperations();
    }

    /**
     * 测试场景：第二或第三个目录在实际移动前发生普通 IOException。
     * 前置条件：失败前的目录已真实移入 archive，失败目录及后续目录从未移动且内容各不相同。
     * 期望结果：只恢复成功记录的目录，所有目录字节与形态不变，且不遗留 .demo-reset operation。
     * 断言重点：补偿不能删除未出现在 movedDirectories 中的正式目标。
     */
    @Test
    void restoresOnlyMovedDirectoriesWhenLaterMoveFails() throws Exception {
        for (int failureAttempt : java.util.List.of(2, 3)) {
            Path scenarioRoot = storageRoot.resolve("failure-" + failureAttempt);
            for (String directory : java.util.List.of("uploads", "skill-snapshots", "skill-runtime")) {
                Path runtimeFile = scenarioRoot.resolve(directory).resolve("original.txt");
                Files.createDirectories(runtimeFile.getParent());
                Files.writeString(runtimeFile, directory + "-" + failureAttempt);
            }
            AtomicInteger moves = new AtomicInteger();
            ManagedStorageSwap swap = new ManagedStorageSwap(scenarioRoot, (source, target) -> {
                if (moves.incrementAndGet() == failureAttempt) {
                    throw new java.io.IOException("模拟后续目录普通移动失败");
                }
                Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            });

            assertThatThrownBy(() -> swap.prepare(UUID.randomUUID()))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("暂存失败");

            for (String directory : java.util.List.of("uploads", "skill-snapshots", "skill-runtime")) {
                org.assertj.core.api.Assertions.assertThat(scenarioRoot.resolve(directory).resolve("original.txt"))
                        .hasContent(directory + "-" + failureAttempt);
            }
            assertDemoResetHasNoOperations(scenarioRoot);
        }
    }

    /**
     * 测试场景：文件系统明确报告不支持 ATOMIC_MOVE，但同一 storageRoot 内普通无覆盖 move 可用。
     * 前置条件：原子移动替身始终抛 AtomicMoveNotSupportedException，普通移动替身调用不带 REPLACE 的 Files.move。
     * 期望结果：prepare 可完成并由 restore 完整恢复三个目录，不留下 operation。
     * 断言重点：降级只针对明确的“不支持原子移动”，且普通移动目标必须事先不存在。
     */
    @Test
    void fallsBackToNonReplacingMoveOnlyWhenAtomicMoveIsUnsupported() throws Exception {
        for (String directory : java.util.List.of("uploads", "skill-snapshots", "skill-runtime")) {
            Path runtimeFile = storageRoot.resolve(directory).resolve("original.txt");
            Files.createDirectories(runtimeFile.getParent());
            Files.writeString(runtimeFile, directory);
        }
        ManagedStorageSwap swap = new ManagedStorageSwap(
                storageRoot,
                (source, target) -> {
                    throw new java.nio.file.AtomicMoveNotSupportedException(
                            source.toString(), target.toString(), "模拟 DrvFS 不支持 ATOMIC_MOVE");
                },
                Files::move);

        ManagedStorageSwap.PreparedStorageSwap prepared = swap.prepare(UUID.randomUUID());
        swap.restore(prepared);

        for (String directory : java.util.List.of("uploads", "skill-snapshots", "skill-runtime")) {
            org.assertj.core.api.Assertions.assertThat(storageRoot.resolve(directory).resolve("original.txt"))
                    .hasContent(directory);
        }
        assertDemoResetHasNoOperations();
    }

    /**
     * 测试场景：ATOMIC_MOVE 不受支持，且随后普通无覆盖 move 也失败。
     * 前置条件：两个移动替身都在改变任何路径前抛出各自异常。
     * 期望结果：prepare 失败，三个未移动目录完整保留，operation 被安全清理。
     * 断言重点：普通 move 的 IOException 不能再次泛化降级或触发未移动目标删除。
     */
    @Test
    void preservesAllDirectoriesWhenAtomicFallbackMoveAlsoFails() throws Exception {
        for (String directory : java.util.List.of("uploads", "skill-snapshots", "skill-runtime")) {
            Path runtimeFile = storageRoot.resolve(directory).resolve("original.txt");
            Files.createDirectories(runtimeFile.getParent());
            Files.writeString(runtimeFile, directory);
        }
        ManagedStorageSwap swap = new ManagedStorageSwap(
                storageRoot,
                (source, target) -> {
                    throw new java.nio.file.AtomicMoveNotSupportedException(
                            source.toString(), target.toString(), "模拟不支持原子移动");
                },
                (source, target) -> {
                    throw new java.io.IOException("模拟普通无覆盖 move 失败");
                });

        assertThatThrownBy(() -> swap.prepare(UUID.randomUUID()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("暂存失败");

        for (String directory : java.util.List.of("uploads", "skill-snapshots", "skill-runtime")) {
            org.assertj.core.api.Assertions.assertThat(storageRoot.resolve(directory).resolve("original.txt"))
                    .hasContent(directory);
        }
        assertDemoResetHasNoOperations();
    }

    /** 当前测试根下不得残留任何一次失败操作目录。 */
    private void assertDemoResetHasNoOperations() throws Exception {
        assertDemoResetHasNoOperations(storageRoot);
    }

    /** 指定场景根下的 .demo-reset 可以不存在；存在时必须为空。 */
    private static void assertDemoResetHasNoOperations(Path scenarioRoot) throws Exception {
        Path resetRoot = scenarioRoot.resolve(".demo-reset");
        if (Files.notExists(resetRoot)) {
            return;
        }
        try (var children = Files.list(resetRoot)) {
            org.assertj.core.api.Assertions.assertThat(children.toList()).isEmpty();
        }
    }
}
