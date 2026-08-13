package com.hsmap.factverification.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

/** 执行一个变体的一条样本；真实 Agent 与固定 fake 都使用同一最小边界。 */
@FunctionalInterface
public interface EvaluationExecutionPort {

    /** 返回已经过统一输出 schema 校验的核验结果。 */
    JsonNode execute(EvaluationExecutionRequest request);
}
