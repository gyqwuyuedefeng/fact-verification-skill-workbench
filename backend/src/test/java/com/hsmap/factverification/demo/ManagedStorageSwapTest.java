package com.hsmap.factverification.demo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hsmap.factverification.shared.ServiceException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.FileStore;
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
        FileStore sharedStore = org.mockito.Mockito.mock(FileStore.class);
        ManagedStorageSwap swap = new ManagedStorageSwap(
                storageRoot,
                (source, target) -> {
                    throw new java.nio.file.AtomicMoveNotSupportedException(
                            source.toString(), target.toString(), "模拟 DrvFS 不支持 ATOMIC_MOVE");
                },
                Files::move,
                path -> sharedStore);

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

    /**
     * 测试场景：ATOMIC_MOVE 不受支持且源、目标最近现存父目录位于不同 FileStore。
     * 前置条件：可控 FileStore 解析器对 source 与 target parent 返回不同实例，并记录普通 move 调用数。
     * 期望结果：失败关闭且不调用普通 move，三个原目录保持原字节。
     * 断言重点：路径同属 storageRoot 不等于同一文件系统，跨 FileStore 不能使用非原子降级。
     */
    @Test
    void refusesOrdinaryFallbackAcrossDifferentFileStores() throws Exception {
        for (String directory : java.util.List.of("uploads", "skill-snapshots", "skill-runtime")) {
            Path runtimeFile = storageRoot.resolve(directory).resolve("original.txt");
            Files.createDirectories(runtimeFile.getParent());
            Files.writeString(runtimeFile, directory);
        }
        FileStore sourceStore = org.mockito.Mockito.mock(FileStore.class);
        FileStore targetStore = org.mockito.Mockito.mock(FileStore.class);
        AtomicInteger ordinaryMoves = new AtomicInteger();
        ManagedStorageSwap swap = new ManagedStorageSwap(
                storageRoot,
                (source, target) -> {
                    throw new java.nio.file.AtomicMoveNotSupportedException(
                            source.toString(), target.toString(), "模拟不支持原子移动");
                },
                (source, target) -> ordinaryMoves.incrementAndGet(),
                path -> path.startsWith(storageRoot.resolve("uploads")) ? sourceStore : targetStore);

        assertThatThrownBy(() -> swap.prepare(UUID.randomUUID()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("暂存失败");
        org.assertj.core.api.Assertions.assertThat(ordinaryMoves).hasValue(0);
        for (String directory : java.util.List.of("uploads", "skill-snapshots", "skill-runtime")) {
            org.assertj.core.api.Assertions.assertThat(storageRoot.resolve(directory).resolve("original.txt"))
                    .hasContent(directory);
        }
    }

    /**
     * 测试场景：FileStore 查询本身失败。
     * 前置条件：原子移动明确不支持，解析器在普通 move 前抛 IOException。
     * 期望结果：普通 move 从未调用，正式目录保持原样。
     * 断言重点：无法证明同一文件系统等价于不同 FileStore，必须失败关闭。
     */
    @Test
    void refusesOrdinaryFallbackWhenFileStoreCannotBeRead() throws Exception {
        Path original = storageRoot.resolve("uploads/original.txt");
        Files.createDirectories(original.getParent());
        Files.writeString(original, "original");
        AtomicInteger ordinaryMoves = new AtomicInteger();
        ManagedStorageSwap swap = new ManagedStorageSwap(
                storageRoot,
                (source, target) -> {
                    throw new java.nio.file.AtomicMoveNotSupportedException(
                            source.toString(), target.toString(), "模拟不支持原子移动");
                },
                (source, target) -> ordinaryMoves.incrementAndGet(),
                path -> {
                    throw new java.io.IOException("模拟 FileStore 查询失败");
                });

        assertThatThrownBy(() -> swap.prepare(UUID.randomUUID()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("暂存失败");
        org.assertj.core.api.Assertions.assertThat(ordinaryMoves).hasValue(0);
        org.assertj.core.api.Assertions.assertThat(original).hasContent("original");
    }

    /**
     * 测试场景：COPY_VERIFY 配置的 storageRoot 本身是指向另一棵真实目录的符号链接。
     * 前置条件：链接目标包含三个正式目录及代表文件；当前文件系统不支持创建链接时显式跳过。
     * 期望结果：prepare 在任何复制、创建 operation 或删除前失败关闭，链接目标全部字节保持不变。
     * 断言重点：不能先用 Files.createDirectories 跟随 storageRoot，再事后检查链接身份。
     */
    @Test
    void copyVerifyRejectsSymlinkStorageRootBeforeTouchingTarget() throws Exception {
        Path linkedTarget = storageRoot.resolve("linked-target");
        populateThreeNestedTrees(linkedTarget);
        Path linkedRoot = storageRoot.resolve("linked-root");
        try {
            Files.createSymbolicLink(linkedRoot, linkedTarget);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            Assumptions.abort("当前文件系统不支持符号链接测试：" + exception.getClass().getSimpleName());
        }
        ManagedStorageSwap swap = copyVerify(linkedRoot, ManagedStorageSwap.CopyVerifyFaults.NONE);

        assertThatThrownBy(() -> swap.prepare(UUID.randomUUID()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("暂存失败");

        assertThreeNestedTrees(linkedTarget);
        org.assertj.core.api.Assertions.assertThat(linkedTarget.resolve(".demo-reset"))
                .doesNotExist();
    }

    /**
     * 测试场景：COPY_VERIFY 配置的 storageRoot 已存在但只是普通文件。
     * 前置条件：该文件含可识别原字节，周围不存在 operation 或受管目录。
     * 期望结果：prepare 失败且原文件字节不变，不尝试把它当目录补建。
     * 断言重点：storageRoot 必须在 NOFOLLOW 语义下是普通目录。
     */
    @Test
    void copyVerifyRejectsRegularFileStorageRoot() throws Exception {
        Path fileRoot = storageRoot.resolve("storage-file");
        Files.writeString(fileRoot, "不可触碰");
        ManagedStorageSwap swap = copyVerify(fileRoot, ManagedStorageSwap.CopyVerifyFaults.NONE);

        assertThatThrownBy(() -> swap.prepare(UUID.randomUUID()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("暂存失败");

        org.assertj.core.api.Assertions.assertThat(fileRoot).hasContent("不可触碰");
    }

    /**
     * 测试场景：storageRoot 普通但既有 `.demo-reset` 是指向外部目录的 operation 祖先链接。
     * 前置条件：三个正式目录均含原文件，链接目标为空；不支持链接时显式跳过。
     * 期望结果：在创建本次 operation、扫描或复制前拒绝，正式目录与外部目标都不变。
     * 断言重点：物理根门禁必须检查候选路径的每个已存在祖先，而不只检查最终文件。
     */
    @Test
    void copyVerifyRejectsSymlinkOperationAncestorBeforeScanning() throws Exception {
        Path scenarioRoot = storageRoot.resolve("operation-ancestor");
        populateThreeNestedTrees(scenarioRoot);
        Path outside = storageRoot.resolve("outside-reset");
        Files.createDirectory(outside);
        UUID operationId = UUID.randomUUID();
        Path outsideMarker = outside.resolve(operationId.toString()).resolve("must-remain.txt");
        Files.createDirectories(outsideMarker.getParent());
        Files.writeString(outsideMarker, "外部既有现场不可触碰");
        try {
            Files.createSymbolicLink(scenarioRoot.resolve(".demo-reset"), outside);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            Assumptions.abort("当前文件系统不支持符号链接测试：" + exception.getClass().getSimpleName());
        }
        ManagedStorageSwap swap = copyVerify(scenarioRoot, ManagedStorageSwap.CopyVerifyFaults.NONE);

        assertThatThrownBy(() -> swap.prepare(operationId))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("暂存失败");

        assertThreeNestedTrees(scenarioRoot);
        org.assertj.core.api.Assertions.assertThat(outsideMarker)
                .hasContent("外部既有现场不可触碰");
    }

    /** COPY_VERIFY 成功时必须支持缺失、仅 .gitkeep 与嵌套文件三种原形，并能逐字节恢复。 */
    @Test
    void copyVerifyPreservesMissingGitkeepAndNestedShapes() throws Exception {
        Files.createDirectories(storageRoot.resolve("uploads/a/b"));
        Files.writeString(storageRoot.resolve("uploads/a/b/material.txt"), "material");
        Files.createDirectories(storageRoot.resolve("skill-snapshots"));
        Files.writeString(storageRoot.resolve("skill-snapshots/.gitkeep"), "");
        ManagedStorageSwap swap = copyVerify(storageRoot, ManagedStorageSwap.CopyVerifyFaults.NONE);

        ManagedStorageSwap.PreparedStorageSwap prepared = swap.prepare(UUID.randomUUID());
        org.assertj.core.api.Assertions.assertThat(swap.blankState().values()).containsOnly(true);
        swap.restore(prepared);

        org.assertj.core.api.Assertions.assertThat(storageRoot.resolve("uploads/a/b/material.txt"))
                .hasContent("material");
        org.assertj.core.api.Assertions.assertThat(storageRoot.resolve("skill-snapshots/.gitkeep"))
                .isRegularFile();
        org.assertj.core.api.Assertions.assertThat(storageRoot.resolve("skill-runtime")).doesNotExist();
        assertDemoResetHasNoOperations();
    }

    /** 首文件、中途文件及跨目录复制失败时，正式源在全部备份验证前不得发生变化。 */
    @Test
    void copyVerifyLeavesFormalTreesUntouchedForCopyFailures() throws Exception {
        for (int failure : java.util.List.of(1, 2, 4)) {
            Path root = storageRoot.resolve("copy-failure-" + failure);
            populateThreeNestedTrees(root);
            AtomicInteger copies = new AtomicInteger();
            ManagedStorageSwap swap = copyVerify(root, (point, source, target) -> {
                if (point == ManagedStorageSwap.CopyVerifyPoint.BEFORE_COPY_FILE
                        && copies.incrementAndGet() == failure) {
                    throw new java.io.IOException("模拟复制失败");
                }
            });

            assertThatThrownBy(() -> swap.prepare(UUID.randomUUID()))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("暂存失败");
            assertThreeNestedTrees(root);
            assertDemoResetHasNoOperations(root);
        }
    }

    /** 源在复制期变化，或备份被短写/篡改/增删条目时，必须在删除正式源前失败关闭。 */
    @Test
    void copyVerifyRejectsSourceChangeAndBackupManifestMismatch() throws Exception {
        for (String scenario : java.util.List.of("source-change", "truncate", "extra", "missing")) {
            Path root = storageRoot.resolve(scenario);
            populateThreeNestedTrees(root);
            AtomicInteger directories = new AtomicInteger();
            AtomicInteger files = new AtomicInteger();
            ManagedStorageSwap swap = copyVerify(root, (point, source, target) -> {
                if (scenario.equals("source-change")
                        && point == ManagedStorageSwap.CopyVerifyPoint.AFTER_BACKUP_DIRECTORY
                        && directories.incrementAndGet() == 1) {
                    Files.writeString(source.resolve("changed.txt"), "changed");
                }
                if (point == ManagedStorageSwap.CopyVerifyPoint.AFTER_COPY_FILE
                        && files.incrementAndGet() == 1) {
                    if (scenario.equals("truncate")) Files.writeString(target, "x");
                    if (scenario.equals("extra")) Files.writeString(target.getParent().resolve("extra.txt"), "x");
                    if (scenario.equals("missing")) Files.delete(target);
                }
            });

            assertThatThrownBy(() -> swap.prepare(UUID.randomUUID()))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("暂存失败");
            org.assertj.core.api.Assertions.assertThat(root.resolve("uploads/child/file-1.txt"))
                    .hasContent("uploads-1");
        }
    }

    /** 首次/中途删除与建空失败必须从已验证备份恢复三目录原字节，并清理 operation。 */
    @Test
    void copyVerifyCompensatesDeleteAndCreateEmptyFailures() throws Exception {
        for (ManagedStorageSwap.CopyVerifyPoint failedPoint : java.util.List.of(
                ManagedStorageSwap.CopyVerifyPoint.BEFORE_DELETE_FORMAL,
                ManagedStorageSwap.CopyVerifyPoint.AFTER_DELETE_FORMAL,
                ManagedStorageSwap.CopyVerifyPoint.BEFORE_CREATE_EMPTY)) {
            Path root = storageRoot.resolve(failedPoint.name());
            populateThreeNestedTrees(root);
            AtomicInteger hits = new AtomicInteger();
            ManagedStorageSwap swap = copyVerify(root, (point, source, target) -> {
                if (point == failedPoint && hits.incrementAndGet() == 2) {
                    throw new java.io.IOException("模拟正式目录切换失败");
                }
            });

            assertThatThrownBy(() -> swap.prepare(UUID.randomUUID()))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("暂存失败");
            assertThreeNestedTrees(root);
            assertDemoResetHasNoOperations(root);
        }
    }

    /** 补偿本身失败时必须保留已验证 operation，且新异常带原切换异常为 suppressed。 */
    @Test
    void copyVerifyRetainsOperationWhenCompensationFails() throws Exception {
        populateThreeNestedTrees(storageRoot);
        ManagedStorageSwap swap = copyVerify(storageRoot, (point, source, target) -> {
            if (point == ManagedStorageSwap.CopyVerifyPoint.AFTER_DELETE_FORMAL
                    || point == ManagedStorageSwap.CopyVerifyPoint.BEFORE_RESTORE) {
                throw new java.io.IOException("模拟切换及补偿失败");
            }
        });

        assertThatThrownBy(() -> swap.prepare(UUID.randomUUID()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("恢复失败")
                .satisfies(exception -> org.assertj.core.api.Assertions.assertThat(exception.getSuppressed())
                        .isNotEmpty());
        try (var operations = Files.list(storageRoot.resolve(".demo-reset"))) {
            org.assertj.core.api.Assertions.assertThat(operations.toList()).hasSize(1);
        }
    }

    /** import 的 staged 含链接或正式复制后被篡改时必须拒绝，并可由同一 prepared 恢复原空白形态。 */
    @Test
    void copyVerifyRejectsUnsafeOrCorruptedImportAndRestoresBlankShape() throws Exception {
        for (String scenario : java.util.List.of("symlink", "corrupt")) {
            Path root = storageRoot.resolve("import-" + scenario);
            Files.createDirectories(root.resolve("uploads"));
            Files.writeString(root.resolve("uploads/.gitkeep"), "");
            java.util.concurrent.atomic.AtomicBoolean corrupted = new java.util.concurrent.atomic.AtomicBoolean();
            ManagedStorageSwap swap = copyVerify(root, (point, source, target) -> {
                if (scenario.equals("corrupt")
                        && point == ManagedStorageSwap.CopyVerifyPoint.AFTER_FORMAL_COPY_FILE
                        && corrupted.compareAndSet(false, true)) {
                    Files.writeString(target, "corrupt");
                }
            });
            ManagedStorageSwap.PreparedStorageSwap prepared = swap.prepare(UUID.randomUUID());
            Map<String, Path> staged = stagedTrees(root);
            if (scenario.equals("symlink")) {
                try {
                    Files.createSymbolicLink(staged.get("uploads").resolve("link"), Path.of("outside"));
                } catch (UnsupportedOperationException | java.io.IOException exception) {
                    Assumptions.abort("当前文件系统不支持符号链接测试");
                }
            }

            assertThatThrownBy(() -> swap.replaceWith(prepared, staged))
                    .isInstanceOf(ServiceException.class);
            swap.restore(prepared);
            org.assertj.core.api.Assertions.assertThat(root.resolve("uploads/.gitkeep"))
                    .isRegularFile();
            org.assertj.core.api.Assertions.assertThat(root.resolve("skill-snapshots")).doesNotExist();
        }
    }

    /** 构造显式 COPY_VERIFY，测试不依赖 IOException 自动选择模式。 */
    private static ManagedStorageSwap copyVerify(Path root, ManagedStorageSwap.CopyVerifyFaults faults) {
        return new ManagedStorageSwap(
                root, DemoAdminProperties.StorageSwapMode.COPY_VERIFY, 2_000, 500L * 1024 * 1024, faults);
    }

    /** 三目录各含两个文件并至少一层子目录，确保覆盖 DrvFS 不能 rename 的真实形态。 */
    private static void populateThreeNestedTrees(Path root) throws Exception {
        for (String directory : java.util.List.of("uploads", "skill-snapshots", "skill-runtime")) {
            Files.createDirectories(root.resolve(directory).resolve("child"));
            Files.writeString(root.resolve(directory).resolve("child/file-1.txt"), directory + "-1");
            Files.writeString(root.resolve(directory).resolve("child/file-2.txt"), directory + "-2");
        }
    }

    /** 断言失败补偿后所有代表文件逐字节恢复。 */
    private static void assertThreeNestedTrees(Path root) {
        for (String directory : java.util.List.of("uploads", "skill-snapshots", "skill-runtime")) {
            org.assertj.core.api.Assertions.assertThat(root.resolve(directory).resolve("child/file-1.txt"))
                    .hasContent(directory + "-1");
            org.assertj.core.api.Assertions.assertThat(root.resolve(directory).resolve("child/file-2.txt"))
                    .hasContent(directory + "-2");
        }
    }

    /** 构造三个已校验 staged 目录，内容含嵌套文件。 */
    private static Map<String, Path> stagedTrees(Path root) throws Exception {
        Map<String, Path> result = new LinkedHashMap<>();
        for (String directory : java.util.List.of("uploads", "skill-snapshots", "skill-runtime")) {
            Path staged = root.resolve("staged").resolve(directory);
            Files.createDirectories(staged.resolve("child"));
            Files.writeString(staged.resolve("child/file.txt"), directory);
            result.put(directory, staged);
        }
        return result;
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
