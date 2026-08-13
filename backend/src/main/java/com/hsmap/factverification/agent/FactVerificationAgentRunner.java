package com.hsmap.factverification.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.evidence.EvidenceRunClient;
import com.hsmap.factverification.evidence.EvidenceRunClientFactory;
import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.shared.VerificationResultValidator;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** 执行一次固定版本 Agent 并把 streamEvents 转成页面事件，最后校验统一 JSON。 */
@Service
public final class FactVerificationAgentRunner {

    private static final Duration DEFAULT_RUN_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_AGENT_ATTEMPTS = 2;

    private final EvidenceRunClientFactory evidenceClients;
    private final FactVerificationAgentFactory agents;
    private final AgentEventMapper eventMapper = new AgentEventMapper();
    private final ObjectMapper objectMapper;
    private final VerificationResultValidator resultValidator;

    public FactVerificationAgentRunner(
            EvidenceRunClientFactory evidenceClients, FactVerificationAgentFactory agents, ObjectMapper objectMapper) {
        this.evidenceClients = evidenceClients;
        this.agents = agents;
        this.objectMapper = objectMapper;
        this.resultValidator = new VerificationResultValidator(objectMapper, "schemas/verification-result.schema.json");
    }

    /** 每次调用创建、注册并关闭独立 MCP client，任何异常都不能污染其他证据快照。 */
    public JsonNode run(
            UUID runId,
            UUID evidenceSnapshotId,
            String ownerType,
            UUID ownerId,
            AgentVariant variant,
            String prompt,
            Consumer<AgentBusinessEvent> events) {
        return run(runId, evidenceSnapshotId, ownerType, ownerId, variant, prompt, events, DEFAULT_RUN_TIMEOUT);
    }

    /**
     * 在调用方声明的硬截止时间内执行事件流。
     *
     * <p>评测清单会把同一个 timeout 传给每个变体；这里必须直接约束流式订阅，而不能等模型返回后再比较耗时，否则服务端未发送结束帧时评测会永久停留在
     * RUNNING。超时取消订阅后，try-with-resources 继续关闭 Agent 与本次快照独占的 MCP client。
     */
    public JsonNode run(
            UUID runId,
            UUID evidenceSnapshotId,
            String ownerType,
            UUID ownerId,
            AgentVariant variant,
            String prompt,
            Consumer<AgentBusinessEvent> events,
            Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new ServiceException("AGENT_TIMEOUT_INVALID", "Agent 执行超时必须大于零");
        }
        JsonNode validatedResult = null;
        ServiceException lastResultFailure = null;
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        try (EvidenceRunClient evidence = evidenceClients.open(evidenceSnapshotId, ownerType, ownerId);
                ReActAgent agent = agents.create(variant, evidence.toolkit())) {
            RuntimeContext context = RuntimeContext.builder()
                    .sessionId(runId.toString())
                    .userId("single-reviewer")
                    .build();
            String attemptPrompt = prompt;
            for (int attempt = 1; attempt <= MAX_AGENT_ATTEMPTS; attempt++) {
                AttemptOutput output = executeAttempt(
                        agent, context, attemptPrompt, events, remaining(deadlineNanos));
                if (!output.resolvedCompany()) {
                    if (attempt >= MAX_AGENT_ATTEMPTS) {
                        break;
                    }
                    // 只针对本次真实复现的“零主体工具调用”做一次固定纠正，不建立通用重试框架。
                    events.accept(new AgentBusinessEvent(
                            "AGENT_RETRY", Map.of("reason", "REQUIRED_TOOL_MISSING", "attempt", attempt + 1)));
                    attemptPrompt = requiredToolCorrection(prompt);
                    continue;
                }
                try {
                    validatedResult = validateResult(output.resultText());
                    break;
                } catch (ServiceException exception) {
                    lastResultFailure = exception;
                    if (attempt >= MAX_AGENT_ATTEMPTS) {
                        throw exception;
                    }
                    // 公司模型偶发漏 schema 必填字段时，只允许在同一硬截止时间内纠正一次；
                    // 仍重新实际取证并返回完整结果，不能由服务端补字段或伪造主张。
                    events.accept(new AgentBusinessEvent(
                            "AGENT_RETRY", Map.of("reason", exception.getCode(), "attempt", attempt + 1)));
                    attemptPrompt = resultContractCorrection(prompt);
                }
            }
        }
        if (validatedResult != null) {
            return validatedResult;
        }
        if (lastResultFailure != null) {
            throw lastResultFailure;
        }
        throw new ServiceException("AGENT_REQUIRED_TOOL_MISSING", "Agent 未实际调用主体搜索工具");
    }

    /** 把模型文本解析并送入统一结果门禁；解析失败与 schema 失败都保留稳定错误码。 */
    private JsonNode validateResult(String resultText) {
        try {
            JsonNode result = objectMapper.readTree(extractJson(resultText));
            resultValidator.validate(result);
            return result;
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("AGENT_RESULT_INVALID", "Agent 返回结果不是合法 JSON");
        }
    }

    /**
     * 执行单轮 AgentScope 事件流并记录本轮是否真实启动主体搜索工具。
     *
     * <p>这里依据框架 {@code ToolCallStartEvent} 映射后的业务事件判定，而不是相信模型 explanation 中的自然语言。所有轮次共享调用方给出的绝对
     * deadline，因此一次纠正重试不会把 Run Manifest 的超时上限翻倍。
     */
    private AttemptOutput executeAttempt(
            ReActAgent agent,
            RuntimeContext context,
            String prompt,
            Consumer<AgentBusinessEvent> events,
            Duration remaining) {
        AtomicReference<String> resultText = new AtomicReference<>();
        AtomicBoolean streamCompleted = new AtomicBoolean(false);
        AtomicBoolean resolvedCompany = new AtomicBoolean(false);
        agent.streamEvents(prompt, context)
                .doOnNext(event -> {
                    AgentBusinessEvent mapped = eventMapper.map(event);
                    events.accept(mapped);
                    if ("AGENT_RESULT".equals(mapped.type())) {
                        resultText.set(String.valueOf(mapped.payload().get("text")));
                    }
                    if ("TOOL_STARTED".equals(mapped.type())
                            && "resolve_company".equals(mapped.payload().get("tool"))) {
                        resolvedCompany.set(true);
                    }
                })
                .doOnComplete(() -> streamCompleted.set(true))
                // timeout(Duration) 是“相邻事件空闲超时”，持续 token 会不断重置计时；take(Duration)
                // 从订阅开始计算绝对总时长，才能兑现 Run Manifest 的单次硬截止时间。
                .take(remaining)
                .onErrorMap(
                        TimeoutException.class,
                        exception -> new ServiceException("AGENT_EXECUTION_TIMEOUT", "Agent 在限定时间内未完成"))
                .blockLast();
        if (!streamCompleted.get()) {
            throw new ServiceException("AGENT_EXECUTION_TIMEOUT", "Agent 在限定时间内未完成");
        }
        if (resultText.get() == null || resultText.get().isBlank()) {
            throw new ServiceException("AGENT_RESULT_MISSING", "Agent 未返回核验结果");
        }
        return new AttemptOutput(resultText.get(), resolvedCompany.get());
    }

    /** 计算所有内部轮次共享的剩余硬时限，截止后不再发起新的公司模型请求。 */
    private static Duration remaining(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new ServiceException("AGENT_EXECUTION_TIMEOUT", "Agent 在限定时间内未完成");
        }
        return Duration.ofNanos(remainingNanos);
    }

    /** 将原始输入完整重放，并把上一轮缺失的可观测工具行为放在纠正提示最前面。 */
    private static String requiredToolCorrection(String originalPrompt) {
        return """
                硬门禁纠正：上一轮没有真实调用 resolve_company，因此上一轮结果无效。
                必须先实际调用 resolve_company；不得只在 explanation 中声称已经查询。完成真实工具调用后重新返回完整纯 JSON。

                %s
                """
                .formatted(originalPrompt);
    }

    /** 将原始输入完整重放，并要求模型纠正上一轮结构漂移；服务端绝不猜测或补写业务字段。 */
    private static String resultContractCorrection(String originalPrompt) {
        return """
                硬门禁纠正：上一轮最终结果未通过统一输出 JSON Schema，因此上一轮结果无效。
                必须重新实际调用 resolve_company 和本主张所需证据工具，再返回包含全部必填字段的完整纯 JSON；不得输出说明文字或代码围栏。

                %s
                """
                .formatted(originalPrompt);
    }

    /** 兼容模型用代码围栏包裹 JSON 的常见输出，但不尝试修复结构或补字段。 */
    private static String extractJson(String text) {
        String value = text.strip();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return value.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return value;
    }

    /** 一轮模型响应及其可观测主体搜索事实；只在 Runner 内部使用，不形成新的持久化实体。 */
    private record AttemptOutput(String resultText, boolean resolvedCompany) {}
}
