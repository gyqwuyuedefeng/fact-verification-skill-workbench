package com.hsmap.factverification.evaluation;

import java.util.List;
import java.util.UUID;

/** 某冻结版本参与过的原始评测投影；不复制或重新平均四项指标。 */
public record SkillEvaluationSummary(
        UUID versionId,
        int evaluationCount,
        UUID latestEvaluationId,
        UUID registeredEvaluationId,
        List<EvaluationRunView> evaluations) {}
