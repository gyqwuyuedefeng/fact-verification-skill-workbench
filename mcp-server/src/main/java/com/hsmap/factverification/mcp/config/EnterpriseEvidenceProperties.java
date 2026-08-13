package com.hsmap.factverification.mcp.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** MCP Server 只读快照库与 ES 连接边界；密码由环境变量绑定且不得进入日志。 */
@Validated
@ConfigurationProperties(prefix = "enterprise-evidence")
public record EnterpriseEvidenceProperties(
        @NotNull @Valid Snapshot snapshot, @NotNull @Valid Elasticsearch elasticsearch) {

    /** MCP 唯一 PostgreSQL 数据源只能是比赛证据快照库。 */
    public record Snapshot(
            @NotBlank String expectedDatabase, @NotBlank String expectedSchema, boolean verifyOnStartup) {}

    /** 企业事实 ES 的最小 HTTP 连接字段；查询索引与字段由服务端常量白名单决定。 */
    public record Elasticsearch(List<String> addresses, String scheme, String username, String password) {}
}
