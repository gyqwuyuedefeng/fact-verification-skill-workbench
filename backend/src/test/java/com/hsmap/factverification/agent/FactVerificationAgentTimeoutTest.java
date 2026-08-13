package com.hsmap.factverification.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.evidence.EvidenceRunClient;
import com.hsmap.factverification.evidence.EvidenceRunClientFactory;
import com.hsmap.factverification.shared.ServiceException;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 被测试对象：{@link FactVerificationAgentRunner} 的真实流式执行超时边界。
 * 测试目的：证明公司模型已经建立流连接但长期不发送结束事件时，评测不会永久停留在 RUNNING。
 * 覆盖范围：超时异常码、最大等待时长，以及超时后的 Agent/MCP 客户端资源释放。
 * 前置条件：用永不完成的 Agent 事件流稳定复现本次 v1 评测现场出现的阻塞，不连接真实模型或数据库。
 */
class FactVerificationAgentTimeoutTest {

    /**
     * 测试场景：模型流已经创建，但既不完成也不报错。
     * 前置条件：调用方为本次评测显式传入 50 毫秒硬超时，MCP Toolkit 和 Agent 均由 Mock 提供。
     * 期望结果：Runner 在短时间内抛出稳定的 AGENT_EXECUTION_TIMEOUT 业务异常，并退出阻塞。
     * 断言重点：不能只在模型最终返回后统计“已经超时”；异常路径仍必须关闭 Agent 和独占 MCP client。
     */
    @Test
    void stopsNeverEndingModelStreamAtTheDeclaredDeadlineAndClosesResources() {
        EvidenceRunClientFactory clients = mock(EvidenceRunClientFactory.class);
        EvidenceRunClient evidence = mock(EvidenceRunClient.class);
        FactVerificationAgentFactory agents = mock(FactVerificationAgentFactory.class);
        ReActAgent agent = mock(ReActAgent.class);
        Toolkit toolkit = mock(Toolkit.class);
        UUID snapshotId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(clients.open(snapshotId, "EVALUATION", ownerId)).thenReturn(evidence);
        when(evidence.toolkit()).thenReturn(toolkit);
        when(agents.create(any(AgentVariant.class), any(Toolkit.class))).thenReturn(agent);
        when(agent.streamEvents(anyString(), any(RuntimeContext.class))).thenReturn(Flux.never());
        FactVerificationAgentRunner runner = new FactVerificationAgentRunner(clients, agents, new ObjectMapper());

        Instant startedAt = Instant.now();
        assertThatThrownBy(() -> runner.run(
                        UUID.randomUUID(),
                        snapshotId,
                        "EVALUATION",
                        ownerId,
                        AgentVariant.baseline("a".repeat(64)),
                        "固定评测输入",
                        event -> {},
                        Duration.ofMillis(50)))
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("AGENT_EXECUTION_TIMEOUT"));

        assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(2));
        verify(agent).close();
        verify(evidence).close();
    }

    /**
     * 测试场景：模型持续发送文本增量，但始终不发送最终结果和结束事件。
     * 前置条件：事件间隔为 10 毫秒，明显小于调用方声明的 60 毫秒总执行时限。
     * 期望结果：持续事件不能重置总时限，Runner 仍按订阅开始后的绝对截止时间取消流并返回超时异常。
     * 断言重点：本用例专门区分“空闲超时”和“总时长超时”；外层抢占时限防止回归时测试自身永久挂起。
     */
    @Test
    void stopsContinuouslyEmittingStreamAtTheAbsoluteDeadline() {
        EvidenceRunClientFactory clients = mock(EvidenceRunClientFactory.class);
        EvidenceRunClient evidence = mock(EvidenceRunClient.class);
        FactVerificationAgentFactory agents = mock(FactVerificationAgentFactory.class);
        ReActAgent agent = mock(ReActAgent.class);
        Toolkit toolkit = mock(Toolkit.class);
        TextBlockDeltaEvent progress = mock(TextBlockDeltaEvent.class);
        UUID snapshotId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(clients.open(snapshotId, "EVALUATION", ownerId)).thenReturn(evidence);
        when(evidence.toolkit()).thenReturn(toolkit);
        when(agents.create(any(AgentVariant.class), any(Toolkit.class))).thenReturn(agent);
        when(progress.getDelta()).thenReturn("持续输出");
        when(agent.streamEvents(anyString(), any(RuntimeContext.class)))
                .thenReturn(Flux.interval(Duration.ofMillis(10)).map(ignored -> (AgentEvent) progress));
        FactVerificationAgentRunner runner = new FactVerificationAgentRunner(clients, agents, new ObjectMapper());

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThatThrownBy(() -> runner.run(
                                UUID.randomUUID(),
                                snapshotId,
                                "EVALUATION",
                                ownerId,
                                AgentVariant.baseline("a".repeat(64)),
                                "固定评测输入",
                                event -> {},
                                Duration.ofMillis(60)))
                        .isInstanceOfSatisfying(
                                ServiceException.class,
                                exception -> assertThat(exception.getCode()).isEqualTo("AGENT_EXECUTION_TIMEOUT")));

        verify(agent).close();
        verify(evidence).close();
    }
}
