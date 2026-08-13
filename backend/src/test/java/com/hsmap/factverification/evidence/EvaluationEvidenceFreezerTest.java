package com.hsmap.factverification.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.evaluation.dataset.GoldDataset;
import com.hsmap.factverification.evaluation.dataset.GoldDatasetLoader;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 被测试对象：正式评测开始前的 {@link EvaluationEvidenceFreezer}。
 * 测试目的：证明数据集声明的主体与证据请求会先去重、顺序执行并写入同一快照，之后模型线程只能重放。
 * 覆盖范围：v3 三十条数据的六工具请求、跨样本去重、独占 MCP client 生命周期。
 * 前置条件：使用真实仓库数据集和内存只读 AgentTool，不连接模型、ES 或 PostgreSQL。
 */
class EvaluationEvidenceFreezerTest {

    /**
     * 测试场景：三十条样本重复引用五家公司的主体和五类证据工具，另含一个简称歧义查询。
     * 前置条件：Toolkit 注册六个只读 fake 工具，每次调用只增加计数并返回合法空响应。
     * 期望结果：31 个不同的规范化请求各执行一次，MCP client 在冻结结束后关闭。
     * 断言重点：重复请求不能再次直查，且全部调用必须使用同一 evaluation snapshot client。
     */
    @Test
    void freezesDistinctDatasetDeclaredRequestsBeforeEvaluation() {
        ObjectMapper objectMapper = new ObjectMapper();
        GoldDataset dataset = new GoldDatasetLoader(objectMapper, new CanonicalJsonHasher(objectMapper))
                .load(Path.of("../evals/manifest.json"));
        AtomicInteger calls = new AtomicInteger();
        Toolkit toolkit = new Toolkit();
        List.of(
                        "resolve_company",
                        "get_company_profile",
                        "get_company_financials",
                        "get_company_intellectual_property",
                        "get_company_risks",
                        "get_company_relationships")
                .forEach(name -> toolkit.registerAgentTool(new CountingTool(name, calls)));
        McpClientWrapper client = mock(McpClientWrapper.class);
        EvidenceRunClientFactory clients = mock(EvidenceRunClientFactory.class);
        UUID evaluationId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        when(clients.open(snapshotId, "EVALUATION", evaluationId))
                .thenReturn(new EvidenceRunClient(client, toolkit));

        new EvaluationEvidenceFreezer(clients, objectMapper).freeze(evaluationId, snapshotId, dataset);

        assertThat(calls).hasValue(31);
        verify(client).close();
    }

    /** 只记录规范化调用次数的只读工具；返回值本身不参与预取器的业务判断。 */
    private record CountingTool(String name, AtomicInteger calls) implements AgentTool {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "评测证据冻结测试工具";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object");
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
            calls.incrementAndGet();
            return Mono.just(ToolResultBlock.text("{\"items\":[],\"total\":0}"));
        }
    }
}
