package com.hsmap.factverification.agent;

import io.agentscope.core.model.GenerateOptions;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 集中声明 BASELINE、Stable 与 Candidate 必须共享的模型采样参数。
 *
 * <p>评测结论只有在模型和采样条件一致时才可归因于 Skill 差异，因此这里不依赖模型服务的默认值。运行清单也从同一处读取参数，避免
 * Agent 实际参数与报告记录发生漂移。
 */
public final class AgentRuntimeParameters {

    public static final double TEMPERATURE = 0.0D;
    public static final double TOP_P = 1.0D;
    public static final long SEED = 20260812L;
    public static final boolean PARALLEL_TOOL_CALLS = false;
    public static final int MAX_TOKENS = 8192;
    public static final boolean ENABLE_THINKING = false;

    private AgentRuntimeParameters() {}

    /**
     * 创建每个事实核验 Agent 都必须使用的确定性生成选项。
     *
     * <p>关闭并行工具调用是为了保证证据工具调用顺序和快照回放更容易复核；显式关闭思考模式沿用 FireLM
     * 对当前公司模型的生产调用方式，避免模型端点默认长思考挤占结构化结果生成时间。输出上限用于阻断异常无限生成，它不限制评测器并发执行不同样本。
     */
    public static GenerateOptions generateOptions() {
        return GenerateOptions.builder()
                .temperature(TEMPERATURE)
                .topP(TOP_P)
                .seed(SEED)
                .parallelToolCalls(PARALLEL_TOOL_CALLS)
                .maxTokens(MAX_TOKENS)
                .additionalBodyParam("chat_template_kwargs", Map.of("enable_thinking", ENABLE_THINKING))
                .build();
    }

    /** 返回可安全写入 Run Manifest 和评测报告的非敏感参数快照。 */
    public static Map<String, Object> manifestParameters() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("temperature", TEMPERATURE);
        parameters.put("topP", TOP_P);
        parameters.put("seed", SEED);
        parameters.put("parallelToolCalls", PARALLEL_TOOL_CALLS);
        parameters.put("maxTokens", MAX_TOKENS);
        parameters.put("enableThinking", ENABLE_THINKING);
        return Map.copyOf(parameters);
    }
}
