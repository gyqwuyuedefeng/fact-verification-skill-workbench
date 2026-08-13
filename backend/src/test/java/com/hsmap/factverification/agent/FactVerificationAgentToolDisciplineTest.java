package com.hsmap.factverification.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.evidence.EvidenceRunClient;
import com.hsmap.factverification.evidence.EvidenceRunClientFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 被测试对象：{@link FactVerificationAgentRunner} 对企业主体取证工具的执行纪律。
 * 测试目的：防止模型在没有真实调用 {@code resolve_company} 时，仅在 explanation 中声称“已查询”并直接返回证据不足。
 * 覆盖范围：每次顶层调用首轮的原生 tool choice、首次响应缺少主体工具、一次固定纠正重试、第二次真实工具事件以及最终结构化结果返回。
 * 前置条件：使用 Mock AgentScope 事件流精确复现 Word 附件回归中观察到的“零工具调用但声称已查询”行为，不连接真实模型或 MCP。
 */
class FactVerificationAgentToolDisciplineTest {

    /**
     * 测试场景：一次 Agent 顶层调用包含多个 ReAct 推理轮次。
     * 前置条件：固定生成参数未设置 tool choice，Hook 先收到 PRE_CALL，再依次收到两个 PRE_REASONING。
     * 期望结果：只有第一个推理轮次被强制选择 resolve_company，工具执行后的总结轮次恢复自动选择；新的 PRE_CALL 会重新武装。
     * 断言重点：强制策略不得覆盖 temperature 等同条件参数，也不能导致每一轮都被迫调用工具而无法生成最终 JSON。
     */
    @Test
    void forcesCompanyResolutionOnlyForTheFirstReasoningRoundOfEachTopLevelCall() {
        io.agentscope.core.agent.Agent agent = mock(io.agentscope.core.agent.Agent.class);
        RuntimeContext context = RuntimeContext.builder().sessionId("tool-discipline-test").build();
        GenerateOptions fixed = GenerateOptions.builder().temperature(0.0).build();
        RequiredCompanyResolutionMiddleware middleware = new RequiredCompanyResolutionMiddleware();
        List<ReasoningInput> observed = new ArrayList<>();

        middleware
                .onAgent(agent, context, new AgentInput(List.of()), ignored -> Flux.concat(
                        middleware.onReasoning(
                                agent,
                                context,
                                new ReasoningInput(List.of(), List.of(), fixed),
                                reasoning -> {
                                    observed.add(reasoning);
                                    return Flux.empty();
                                }),
                        middleware.onReasoning(
                                agent,
                                context,
                                new ReasoningInput(List.of(), List.of(), fixed),
                                reasoning -> {
                                    observed.add(reasoning);
                                    return Flux.empty();
                                })))
                .blockLast();

        assertThat(observed.get(0).options().getTemperature()).isEqualTo(0.0);
        assertThat(observed.get(0).options().getToolChoice())
                .isInstanceOfSatisfying(
                        ToolChoice.Specific.class,
                        choice -> assertThat(choice.toolName()).isEqualTo("resolve_company"));
        assertThat(observed.get(1).options().getToolChoice()).isNull();

        middleware
                .onAgent(agent, context, new AgentInput(List.of()), ignored -> middleware.onReasoning(
                        agent,
                        context,
                        new ReasoningInput(List.of(), List.of(), fixed),
                        reasoning -> {
                            observed.add(reasoning);
                            return Flux.empty();
                        }))
                .blockLast();
        assertThat(observed.get(2).options().getToolChoice())
                .isInstanceOf(ToolChoice.Specific.class);
    }

    /**
     * 测试场景：第一次模型响应结构合法但没有任何主体搜索事件，第二次响应先真实发出主体搜索事件再返回结果。
     * 前置条件：两个响应使用相同 runId、证据快照和固定采样配置；第二次流只增加缺失的工具行为。
     * 期望结果：Runner 不接受第一次伪查询结果，而是执行一次纠正重试并返回第二次结果。
     * 断言重点：AgentScope 流恰好调用两次、页面事件包含 resolve_company，且最终 JSON 仍通过统一 schema。
     */
    @Test
    void retriesOnceWhenFirstResponseSkipsRequiredCompanyResolutionTool() {
        EvidenceRunClientFactory clients = mock(EvidenceRunClientFactory.class);
        EvidenceRunClient evidence = mock(EvidenceRunClient.class);
        FactVerificationAgentFactory agents = mock(FactVerificationAgentFactory.class);
        ReActAgent agent = mock(ReActAgent.class);
        Toolkit toolkit = mock(Toolkit.class);
        UUID runId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String result = validInsufficientResult(runId, snapshotId);
        when(clients.open(snapshotId, "TASK", ownerId)).thenReturn(evidence);
        when(evidence.toolkit()).thenReturn(toolkit);
        when(agents.create(any(AgentVariant.class), any(Toolkit.class))).thenReturn(agent);
        when(agent.streamEvents(anyString(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(new AgentResultEvent(assistantResult(result))),
                        Flux.just(
                                new ToolCallStartEvent("reply-2", "tool-1", "resolve_company"),
                                new AgentResultEvent(assistantResult(result))));
        List<AgentBusinessEvent> events = new ArrayList<>();
        FactVerificationAgentRunner runner = new FactVerificationAgentRunner(clients, agents, new ObjectMapper());

        JsonNode output = runner.run(
                runId,
                snapshotId,
                "TASK",
                ownerId,
                AgentVariant.baseline("a".repeat(64)),
                "核验云岚数据（苏州）有限公司的明确企业事实",
                events::add,
                Duration.ofSeconds(2));

        assertThat(output.path("claims")).hasSize(1);
        assertThat(events)
                .anySatisfy(event -> {
                    assertThat(event.type()).isEqualTo("TOOL_STARTED");
                    assertThat(event.payload()).containsEntry("tool", "resolve_company");
                });
        verify(agent, times(2)).streamEvents(anyString(), any(RuntimeContext.class));
    }

    /**
     * 测试场景：第一次响应已经真实调用主体工具，但最终 JSON 漏掉统一输出 schema 的必填字段；第二次响应结构完整。
     * 前置条件：两次响应共用同一绝对截止时间，且第二次仍由中间件强制先执行 {@code resolve_company}。
     * 期望结果：Runner 不把模型偶发结构漂移直接暴露成任务失败，而是只做一次带明确原因的纠正重试。
     * 断言重点：最终结果通过 schema、事件包含 {@code RESULT_SCHEMA_INVALID} 重试原因，模型流恰好调用两次。
     */
    @Test
    void retriesOnceWhenFirstToolBackedResultViolatesOutputSchema() {
        EvidenceRunClientFactory clients = mock(EvidenceRunClientFactory.class);
        EvidenceRunClient evidence = mock(EvidenceRunClient.class);
        FactVerificationAgentFactory agents = mock(FactVerificationAgentFactory.class);
        ReActAgent agent = mock(ReActAgent.class);
        Toolkit toolkit = mock(Toolkit.class);
        UUID runId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String validResult = validInsufficientResult(runId, snapshotId);
        when(clients.open(snapshotId, "TASK", ownerId)).thenReturn(evidence);
        when(evidence.toolkit()).thenReturn(toolkit);
        when(agents.create(any(AgentVariant.class), any(Toolkit.class))).thenReturn(agent);
        when(agent.streamEvents(anyString(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.just(
                                new ToolCallStartEvent("reply-1", "tool-1", "resolve_company"),
                                new AgentResultEvent(assistantResult("{\"runId\":\"" + runId + "\"}"))),
                        Flux.just(
                                new ToolCallStartEvent("reply-2", "tool-2", "resolve_company"),
                                new AgentResultEvent(assistantResult(validResult))));
        List<AgentBusinessEvent> events = new ArrayList<>();
        FactVerificationAgentRunner runner = new FactVerificationAgentRunner(clients, agents, new ObjectMapper());

        JsonNode output = runner.run(
                runId,
                snapshotId,
                "TASK",
                ownerId,
                AgentVariant.baseline("a".repeat(64)),
                "核验云岚数据（苏州）有限公司的明确企业事实",
                events::add,
                Duration.ofSeconds(2));

        assertThat(output.path("claims")).hasSize(1);
        assertThat(events)
                .anySatisfy(event -> {
                    assertThat(event.type()).isEqualTo("AGENT_RETRY");
                    assertThat(event.payload()).containsEntry("reason", "RESULT_SCHEMA_INVALID");
                });
        verify(agent, times(2)).streamEvents(anyString(), any(RuntimeContext.class));
    }

    /** 构造 AgentScope 最终消息；测试只关心消息中的纯 JSON 文本，不模拟 token 增量。 */
    private static Msg assistantResult(String text) {
        return Msg.builder().name("assistant").role(MsgRole.ASSISTANT).textContent(text).build();
    }

    /** 构造满足统一结果 schema 的最小证据不足结果，确保 RED 只由缺少工具纠正重试导致。 */
    private static String validInsufficientResult(UUID runId, UUID snapshotId) {
        return """
                {
                  "runId":"%s",
                  "variant":{"type":"BASELINE","identifier":"BASELINE","contentHash":"%s"},
                  "documentSnapshotHash":"%s",
                  "evidenceSnapshotId":"%s",
                  "claims":[{
                    "claimId":"claim-1",
                    "claimText":"统一社会信用代码为 91320500MA2DEMO006。",
                    "materialLocator":{"fileId":"fixture","lineStart":1,"lineEnd":1},
                    "normalizedClaim":{"metric":"unifiedSocialCreditCode","period":"CURRENT","operator":"EQUALS","value":"91320500MA2DEMO006","unit":null},
                    "subject":null,
                    "status":"INSUFFICIENT",
                    "riskFlags":["SUBJECT_NOT_FOUND"],
                    "evidence":[],
                    "explanation":"主体未找到。",
                    "requiresHumanIntervention":true
                  }]
                }
                """
                .formatted(runId, "a".repeat(64), "b".repeat(64), snapshotId);
    }
}
