package com.hsmap.factverification.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 被测试对象：MCP 快照 PostgreSQL 数据源配置。
 * 测试目的：确保 MCP 只能读取比赛证据快照，企业事实本身只能来自只读 ES 白名单。
 * 覆盖范围：独立环境变量、连接池只读提示、PostgreSQL 会话级只读默认值和数据源数量。
 * 前置条件：测试只读取 classpath 配置文本，不连接任何数据库。
 */
class ReadOnlyBoundaryTest {

    /**
     * 测试场景：MCP 使用独立快照连接并在 PostgreSQL 会话层强制只读。
     * 前置条件：普通测试账号本身可能允许写，Hikari 的 read-only 标记也不保证 autocommit 查询报告只读。
     * 期望结果：配置同时声明连接只读提示和 default_transaction_read_only 会话初始化。
     * 断言重点：MCP 不复用 Agent 写数据源变量，且不能只依赖 JDBC hint。
     */
    @Test
    void snapshotDatasourceIsReadOnlyAndIndependentFromWriter() throws IOException {
        String configuration = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);

        assertThat(configuration)
                .contains("url: ${SNAPSHOT_DB_URL:")
                .contains("read-only: true")
                .contains("connection-init-sql: SET default_transaction_read_only = on")
                .doesNotContain("APP_DB_URL", "APP_DB_USERNAME", "APP_DB_PASSWORD");
    }

    /**
     * 测试场景：MCP 只声明比赛快照库和 Elasticsearch 两类来源。
     * 前置条件：企业事实查询不允许直连 metastart 的业务 PostgreSQL 数据源。
     * 期望结果：固定 test schema，且配置中没有第二、第三业务数据源。
     * 断言重点：不会因为复用旧代码而把公司业务库写连接带入比赛 MCP。
     */
    @Test
    void containsNoCompanyBusinessPostgresDatasource() throws IOException {
        String configuration = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);

        assertThat(configuration)
                .contains("currentSchema=test")
                .contains("enterprise-evidence:")
                .contains("elasticsearch:")
                .doesNotContain("secondary-datasource", "db2:", "db3:");
    }
}
