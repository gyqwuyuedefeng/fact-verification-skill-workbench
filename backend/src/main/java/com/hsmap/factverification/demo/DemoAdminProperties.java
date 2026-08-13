package com.hsmap.factverification.demo;

import jakarta.validation.constraints.Positive;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 比赛演示管理端的最小配置边界。
 *
 * <p>该配置只服务于 test profile 下的快照导入和状态清空；它不提供通用备份、任意目录或任意表管理能力。
 */
@Validated
@ConfigurationProperties(prefix = "workbench.demo-admin")
public record DemoAdminProperties(
        boolean enabled,
        Path demoMaterialRoot,
        Path skillPresetRoot,
        @Positive long maxArchiveBytes,
        @Positive int maxEntryCount,
        @Positive long maxExpandedBytes) {

    /** 保留 Task 5 包内测试构造合同；旧用例不消费 preset 根，默认指向正式相对路径。 */
    DemoAdminProperties(
            boolean enabled,
            Path demoMaterialRoot,
            long maxArchiveBytes,
            int maxEntryCount,
            long maxExpandedBytes) {
        this(enabled, demoMaterialRoot, Path.of("skills/presets"), maxArchiveBytes, maxEntryCount, maxExpandedBytes);
    }
}
