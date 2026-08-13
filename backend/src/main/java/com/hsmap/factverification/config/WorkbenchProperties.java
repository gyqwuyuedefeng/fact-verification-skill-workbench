package com.hsmap.factverification.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 从环境变量映射工作台运行边界；凭据仍由 Spring datasource 管理，不进入该对象。 */
@Validated
@ConfigurationProperties(prefix = "workbench")
public record WorkbenchProperties(
        @NotNull @Valid DatabaseBoundary database,
        @NotNull Path storageRoot,
        @NotNull Path evaluationManifest,
        @NotNull Path skillSourceRoot,
        @NotNull @Valid Model model,
        @NotNull URI mcpEndpoint) {

    /** 只有批准的 database/schema 身份可以承载比赛数据。 */
    public record DatabaseBoundary(
            @NotBlank String expectedName, @NotBlank String expectedSchema, boolean verifyOnStartup) {}

    /** 公司千问的 OpenAI-compatible 非敏感连接字段；apiKey 不得写入日志或识别值。 */
    public record Model(String url, String endpointPath, String id, String apiKey) {}
}
