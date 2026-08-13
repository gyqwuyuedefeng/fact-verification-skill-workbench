package com.hsmap.factverification.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.evidence.persistence.EvidenceSnapshotRepository;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/**
 * 被测试对象：MCP 六工具返回结果的证据快照记录器。
 * 测试目的：证明 AgentTool 直接返回最终 ToolResultBlock 时也会真正写入不可变快照，而不是只监听可选的流式 chunk。
 * 覆盖范围：工具包装、成功结果落库和原始参数传递。
 * 前置条件：AgentScope MCP 工具通常直接返回最终结果，不保证调用 ToolEmitter 发送中间 chunk。
 */
class EvidenceSnapshotRecorderTest {

    /**
     * 测试场景：注册一个模拟 MCP 只读工具，安装记录器后直接执行最终结果调用。
     * 前置条件：模拟工具不发送任何 chunk，只返回一个 JSON ToolResultBlock。
     * 期望结果：仓储 append 被调用一次，确保真实 MCP 运行能产生可重放证据快照。
     * 断言重点：不能依赖 setChunkCallback 才记录结果。
     */
    @Test
    void recordsDirectMcpToolResultWithoutChunkEmission() {
        EvidenceSnapshotRepository repository = org.mockito.Mockito.mock(EvidenceSnapshotRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        EvidenceSnapshotRecorder recorder =
                new EvidenceSnapshotRecorder(repository, objectMapper, new CanonicalJsonHasher(objectMapper));
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new DirectResultTool());
        UUID snapshotId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        recorder.attach(toolkit, snapshotId, "TASK", ownerId);

        ToolUseBlock use = new ToolUseBlock(
                "tool-call-1", "resolve_company", Map.of("query", "科大讯飞股份有限公司"));
        ToolCallParam call = ToolCallParam.builder()
                .toolUseBlock(use)
                .input(use.getInput())
                .build();
        toolkit.getTool("resolve_company").callAsync(call).block();

        ArgumentCaptor<EvidenceSnapshotRepository.SnapshotRow> row =
                ArgumentCaptor.forClass(EvidenceSnapshotRepository.SnapshotRow.class);
        verify(repository).append(row.capture());
        assertThat(row.getValue().errorCode()).isNull();
        assertThat(row.getValue().responseJson()).contains("C001");
    }

    /** 模拟 AgentScope MCP 工具的直接最终响应形态。 */
    private static final class DirectResultTool implements AgentTool {

        @Override
        public String getName() {
            return "resolve_company";
        }

        @Override
        public String getDescription() {
            return "模拟只读主体解析";
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
            return Mono.just(ToolResultBlock.text("{\"subject\":{\"companyId\":\"C001\"}}"));
        }
    }
}
