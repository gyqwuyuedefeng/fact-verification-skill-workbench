package com.hsmap.factverification.evaluation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 两个 Skill 在同一冻结评测中的直接比较；不可比时只返回原因。 */
public record EvaluationComparison(
        boolean comparable,
        UUID leftVersionId,
        UUID rightVersionId,
        UUID evaluationRunId,
        List<String> reasons,
        Map<String, Double> metricDeltas,
        Map<String, Integer> sampleOutcomes,
        Map<String, Integer> failureTypeChanges) {}
