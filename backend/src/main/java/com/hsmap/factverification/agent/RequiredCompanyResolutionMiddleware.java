package com.hsmap.factverification.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 对每次企业核验顶层调用的首个推理轮次强制选择 {@code resolve_company}。
 *
 * <p>公司模型在真实 Word 附件中曾跳过工具，却在 explanation 中虚构“已经调用”。提示词无法把自然语言承诺变成可验证行为，因此这里使用
 * AgentScope 原生 {@link ToolChoice.Specific} 在模型请求层锁定首个动作。后续 ReAct 轮次恢复自动选择，保证模型仍能调用业务证据工具并最终生成
 * JSON；本类只解决已复现的首个主体搜索缺口，不实现通用编排器。
 */
public final class RequiredCompanyResolutionMiddleware implements MiddlewareBase {

    private static final String REQUIRED_TOOL = "resolve_company";

    /** 每个 Agent 实例仅服务一个运行；原子标记防止流式回调切换线程时重复强制。 */
    private final AtomicBoolean forceNextReasoning = new AtomicBoolean(false);

    /** 每次 {@code streamEvents} 顶层调用重新武装一次，固定纠正重试也会获得相同的首轮约束。 */
    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext context,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        forceNextReasoning.set(true);
        return next.apply(input);
    }

    /**
     * 仅修改首轮 reasoning 的 tool choice，并通过 {@code mergeOptions} 保留温度、seed、思考开关和输出上限等同条件参数。
     */
    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext context,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (!forceNextReasoning.compareAndSet(true, false)) {
            return next.apply(input);
        }
        GenerateOptions requiredTool = GenerateOptions.builder()
                .toolChoice(new ToolChoice.Specific(REQUIRED_TOOL))
                .build();
        GenerateOptions options = GenerateOptions.mergeOptions(requiredTool, input.options());
        return next.apply(new ReasoningInput(input.messages(), input.tools(), options));
    }
}
