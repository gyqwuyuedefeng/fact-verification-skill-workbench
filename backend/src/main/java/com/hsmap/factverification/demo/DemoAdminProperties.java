package com.hsmap.factverification.demo;

import jakarta.validation.constraints.Positive;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
        @Positive long maxExpandedBytes,
        StorageSwapMode storageSwapMode) {

    /**
     * 明确指定配置绑定使用完整规范构造器。
     *
     * <p>本 record 为旧测试保留了一个五参数兼容构造器；Spring Boot 4 在存在多个构造器时不会自行猜测，必须由此注解固定生产装配边界。
     */
    @ConstructorBinding
    public DemoAdminProperties {
        storageSwapMode = storageSwapMode == null ? StorageSwapMode.MOVE : storageSwapMode;
    }

    /** MOVE 保留原目录交换；COPY_VERIFY 只由 test profile 显式选择，禁止运行时按异常自动猜测。 */
    public enum StorageSwapMode {
        MOVE,
        COPY_VERIFY
    }

    /** 保留既有完整测试构造合同；未显式指定策略时必须稳定使用 MOVE。 */
    DemoAdminProperties(
            boolean enabled,
            Path demoMaterialRoot,
            Path skillPresetRoot,
            long maxArchiveBytes,
            int maxEntryCount,
            long maxExpandedBytes) {
        this(
                enabled,
                demoMaterialRoot,
                skillPresetRoot,
                maxArchiveBytes,
                maxEntryCount,
                maxExpandedBytes,
                StorageSwapMode.MOVE);
    }

    /** 保留 Task 5 包内测试构造合同；旧用例不消费 preset 根，默认指向正式相对路径。 */
    DemoAdminProperties(
            boolean enabled,
            Path demoMaterialRoot,
            long maxArchiveBytes,
            int maxEntryCount,
            long maxExpandedBytes) {
        this(
                enabled,
                demoMaterialRoot,
                Path.of("skills/presets"),
                maxArchiveBytes,
                maxEntryCount,
                maxExpandedBytes,
                StorageSwapMode.MOVE);
    }
}
