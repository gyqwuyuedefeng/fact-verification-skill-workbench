package com.hsmap.factverification.mcp.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hsmap.factverification.mcp.EnterpriseEvidenceMcpApplication;
import com.hsmap.factverification.mcp.query.LiveEvidenceQuery;
import com.hsmap.factverification.mcp.query.SnapshotReplayLookup;
import com.hsmap.factverification.mcp.tool.EnterpriseEvidenceTools;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 被测试对象：企业证据 MCP 的六工具合同、查询白名单和 Streamable HTTP 边界。
 * 测试目的：锁定“只读查询”不仅是 Java 实现约定，也必须是客户端可见的 MCP 协议元数据。
 * 覆盖范围：六个工具名与只读注解、十二索引字段白名单、快照优先、闭合参数和旧 SSE 端点禁用。
 * 前置条件：使用 H2 和假查询网关，不访问公司 ES 或共享 PostgreSQL。
 */
@SpringBootTest(
        classes = EnterpriseEvidenceMcpApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.ai.mcp.server.protocol=STREAMABLE",
            "spring.datasource.url=jdbc:h2:mem:mcpcontract;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.hikari.read-only=true",
            "enterprise-evidence.snapshot.verify-on-startup=false",
            "enterprise-evidence.elasticsearch.addresses=",
            "spring.flyway.enabled=false"
        })
class EnterpriseEvidenceMcpContractTest {

    @LocalServerPort
    int serverPort;

    /**
     * 测试场景：检查服务端声明的工具集合。
     * 前置条件：只扫描 {@link EnterpriseEvidenceTools} 上的 MCP 注解方法。
     * 期望结果：工具名称恰好是设计批准的六个，不包含旧服务的额外入口。
     * 断言重点：名称集合双向精确匹配，防止多注册或少注册。
     */
    @Test
    void registersExactlySixApprovedTools() {
        Set<String> toolNames = java.util.Arrays.stream(EnterpriseEvidenceTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(McpTool.class))
                .map(method -> method.getAnnotation(McpTool.class).name())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(toolNames)
                .containsExactlyInAnyOrder(
                        "resolve_company",
                        "get_company_profile",
                        "get_company_financials",
                        "get_company_intellectual_property",
                        "get_company_risks",
                        "get_company_relationships");
    }

    /**
     * 测试场景：AgentScope 在评测预取前根据 MCP ToolAnnotations 判断工具是否可安全调用。
     * 前置条件：Spring AI 的 {@link McpTool} 默认值是 readOnly=false、destructive=true，因此六个方法必须显式覆盖。
     * 期望结果：每个工具都对外声明只读、非破坏性且幂等。
     * 断言重点：任一工具遗漏协议元数据都会使合同失败，不允许仅依赖“实现里没有写操作”。
     */
    @Test
    void advertisesEveryToolAsReadOnlyNonDestructiveAndIdempotent() {
        java.util.Arrays.stream(EnterpriseEvidenceTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(McpTool.class))
                .forEach(method -> {
                    McpTool.McpAnnotations annotations = method.getAnnotation(McpTool.class).annotations();
                    assertThat(annotations.readOnlyHint()).as(method.getName()).isTrue();
                    assertThat(annotations.destructiveHint()).as(method.getName()).isFalse();
                    assertThat(annotations.idempotentHint()).as(method.getName()).isTrue();
                });
    }

    /**
     * 测试场景：查询计划选择 ES 索引与返回字段。
     * 前置条件：以代码中唯一的 EvidenceToolCatalog 为权威目录。
     * 期望结果：只能触达批准的十二个索引，每个索引都有显式字段白名单。
     * 断言重点：禁止通配字段以及 password/token 等敏感字段。
     */
    @Test
    void usesExactlyTwelveWhitelistedIndicesAndFields() {
        assertThat(EvidenceToolCatalog.allowedIndices()).hasSize(12);
        assertThat(EvidenceToolCatalog.allowedIndices())
                .contains(
                        "ads_lget_company_info",
                        "ads_lget_company_revenue",
                        "ads_lget_patent_info",
                        "ads_lget_company_lose_trust",
                        "ads_lget_company_supply");
        EvidenceToolCatalog.indexPolicies().forEach(policy -> {
            assertThat(policy.sourceFields()).isNotEmpty().doesNotContain("*");
            assertThat(policy.sourceFields()).doesNotContain("password", "token");
        });
    }

    /**
     * 测试场景：同一 snapshot/tool/规范参数已经存在不可变证据。
     * 前置条件：快照查询返回命中值，live 网关用计数器观测。
     * 期望结果：执行器直接重放快照，不发起 ES 查询。
     * 断言重点：live 调用次数必须为零，保证同条件评测。
     */
    @Test
    void replaysSnapshotBeforeLiveQuery() {
        UUID snapshotId = UUID.fromString("9e90e8f4-a462-4d22-8cc0-1a31d9ad640c");
        AtomicInteger liveCalls = new AtomicInteger();
        SnapshotReplayLookup replay = (id, tool, argumentsHash) -> Optional.of(Map.of(
                "subject",
                Map.of("companyId", "C001", "companyName", "火石科技"),
                "items",
                java.util.List.of(),
                "evidence",
                java.util.List.of(),
                "total",
                0,
                "truncated",
                false,
                "asOf",
                "2026-08-12T00:00:00Z"));
        LiveEvidenceQuery live = (tool, arguments) -> {
            liveCalls.incrementAndGet();
            return Map.of("unexpected", true);
        };
        EvidenceToolExecutor executor = new EvidenceToolExecutor(replay, live);

        Object result =
                executor.execute(EvidenceToolName.GET_COMPANY_PROFILE, Map.of("companyId", " C001 "), snapshotId);

        assertThat(result).isInstanceOf(Map.class);
        assertThat(liveCalls).hasValue(0);
    }

    /**
     * 测试场景：客户端提交过短主体词或工具 schema 外的额外字段。
     * 前置条件：快照不命中，live 查询不应被非法输入触发。
     * 期望结果：所有非法参数在 ES 之前被稳定拒绝。
     * 断言重点：对外错误码统一为 MCP_ARGUMENT_INVALID。
     */
    @Test
    void validatesClosedToolInputs() {
        EvidenceToolExecutor executor =
                new EvidenceToolExecutor((id, tool, hash) -> Optional.empty(), (tool, args) -> Map.of());

        assertThatThrownBy(() ->
                        executor.execute(EvidenceToolName.RESOLVE_COMPANY, Map.of("query", "x"), UUID.randomUUID()))
                .hasMessageContaining("MCP_ARGUMENT_INVALID");
        assertThatThrownBy(() -> executor.execute(
                        EvidenceToolName.GET_COMPANY_PROFILE,
                        Map.of("companyId", "C001", "unexpected", true),
                        UUID.randomUUID()))
                .hasMessageContaining("MCP_ARGUMENT_INVALID");
    }

    /**
     * 测试场景：请求旧 GET SSE 握手和独立 message POST 端点。
     * 前置条件：以随机端口启动完整 MCP WebMVC 应用。
     * 期望结果：两个旧端点均不存在，唯一 MCP 入口保持 `/mcp` Streamable HTTP。
     * 断言重点：`/sse` 与 `/mcp/message` 都必须返回 404。
     */
    @Test
    void legacyMcpEndpointsReturnNotFound() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<Void> oldHandshake = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/" + "sse"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        HttpResponse<Void> oldMessage = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/mcp/" + "message"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(oldHandshake.statusCode()).isEqualTo(404);
        assertThat(oldMessage.statusCode()).isEqualTo(404);
    }
}
