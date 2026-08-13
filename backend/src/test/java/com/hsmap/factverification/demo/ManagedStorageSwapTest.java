package com.hsmap.factverification.demo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hsmap.factverification.shared.ServiceException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
}
