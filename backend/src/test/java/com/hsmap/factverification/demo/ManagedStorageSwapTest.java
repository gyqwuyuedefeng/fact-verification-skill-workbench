package com.hsmap.factverification.demo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hsmap.factverification.shared.ServiceException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 被测试对象：ManagedStorageSwap 的固定受管目录边界。
 * 测试目的：确保目录交换只允许在 storageRoot 内操作，避免演示管理功能扩展为任意路径删除工具。
 * 覆盖范围：规范化后的路径边界异常。
 * 前置条件：storageRoot 使用一个不含三个标准运行子目录的普通相对路径，模拟错误配置输入。
 */
class ManagedStorageSwapTest {

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
}
