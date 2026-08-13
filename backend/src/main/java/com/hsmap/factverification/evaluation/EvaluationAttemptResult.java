package com.hsmap.factverification.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

/** 单次原始输出与执行摘要，用于稳定性复核。 */
public record EvaluationAttemptResult(int attempt, JsonNode output, long durationMs, String errorCode) {}
