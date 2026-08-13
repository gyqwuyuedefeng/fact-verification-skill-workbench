package com.hsmap.factverification.mcp.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hsmap.factverification.mcp.config.EnterpriseEvidenceProperties;
import com.hsmap.factverification.mcp.contract.EvidenceToolName;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 被测试对象：{@link EsEvidenceQuery} 的 ES 命中记录转换逻辑。
 * 测试目的：保证公司 dev ES 中合法存在的 null 字段不会让只读 MCP 工具调用失败。
 * 覆盖范围：真实 HTTP 响应解析、null 字段保留以及返回记录的只读约束。
 * 前置条件：使用进程内 HTTP Server 模拟固定白名单索引，不连接公司 ES 或共享数据库。
 */
class EsEvidenceQueryTest {

    /**
     * 测试场景：主体搜索命中记录中的简称、曾用名等白名单字段为 null。
     * 前置条件：模拟 ES 返回一条包含 null 字段的合法 `_source`，查询参数为公司全称。
     * 期望结果：查询正常返回并原样保留 null，同时调用方不能修改返回记录。
     * 断言重点：不再因不可变 Map 的 null 限制抛异常，且只读证据边界保持不变。
     */
    @Test
    void preservesNullableSourceFieldsInReadOnlyEvidenceItem() throws Exception {
        HttpServer server = startElasticsearchStub();
        try {
            EnterpriseEvidenceProperties properties = new EnterpriseEvidenceProperties(
                    new EnterpriseEvidenceProperties.Snapshot("kjjr_inx_brain", "test", false),
                    new EnterpriseEvidenceProperties.Elasticsearch(
                            List.of("127.0.0.1:" + server.getAddress().getPort()), "http", "", ""));
            EsEvidenceQuery query = new EsEvidenceQuery(properties);

            EsEvidenceEnvelope result =
                    (EsEvidenceEnvelope) query.query(EvidenceToolName.RESOLVE_COMPANY, Map.of("query", "科大讯飞股份有限公司"));

            assertThat(result.total()).isEqualTo(1);
            assertThat(result.items()).singleElement().satisfies(item -> {
                assertThat(item).containsEntry("company_name", "科大讯飞股份有限公司");
                assertThat(item).containsEntry("company_sname", null);
                assertThatThrownBy(() -> item.put("company_name", "被篡改"))
                        .isInstanceOf(UnsupportedOperationException.class);
            });
        } finally {
            server.stop(0);
        }
    }

    /**
     * 测试场景：同一家企业存在超过工具返回上限的多个财务报告期。
     * 前置条件：使用 HTTP 替身捕获发往固定财务索引的实际 ES 请求体，返回空命中以隔离响应排序因素。
     * 期望结果：请求明确按 report_year 降序且缺失年份置后，并固定返回最多十个报告期，使2020年的演示数据不会被近五年截断。
     * 断言重点：排序和上限必须由服务端固定，模型输入和 companyId 都不能改变查询策略。
     */
    @Test
    void sortsFinancialEvidenceByNewestReportYear() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = startFinancialElasticsearchStub(capturedBody);
        try {
            EnterpriseEvidenceProperties properties = new EnterpriseEvidenceProperties(
                    new EnterpriseEvidenceProperties.Snapshot("kjjr_inx_brain", "test", false),
                    new EnterpriseEvidenceProperties.Elasticsearch(
                            List.of("127.0.0.1:" + server.getAddress().getPort()), "http", "", ""));
            EsEvidenceQuery query = new EsEvidenceQuery(properties);

            query.query(EvidenceToolName.GET_COMPANY_FINANCIALS, Map.of("companyId", "company-1"));

            String request = capturedBody.get().replaceAll("\\s+", "");
            assertThat(request)
                    .contains("\"size\":10")
                    .contains("\"sort\"")
                    .contains("\"report_year\"")
                    .contains("\"order\":\"desc\"")
                    .contains("\"missing\":\"_last\"");
        } finally {
            server.stop(0);
        }
    }

    /** 启动只返回一条合法主体命中的 ES 替身，避免单元测试依赖任何内网环境。 */
    private static HttpServer startElasticsearchStub() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ads_lget_company_info/_search", exchange -> {
            byte[] response =
                    """
                    {"hits":{"total":{"value":1},"hits":[{"_id":"company-1","_source":{
                      "company_code":"002230","company_name":"科大讯飞股份有限公司","company_sname":null
                    }}]}}
                    """
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    /** 启动财务索引替身并记录请求体，用于验证固定查询策略而不依赖真实 ES 命中顺序。 */
    private static HttpServer startFinancialElasticsearchStub(AtomicReference<String> capturedBody)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ads_lget_company_revenue/_search", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"hits\":{\"total\":{\"value\":0},\"hits\":[]}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }
}
