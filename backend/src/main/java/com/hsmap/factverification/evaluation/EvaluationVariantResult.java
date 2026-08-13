package com.hsmap.factverification.evaluation;

import com.hsmap.factverification.evaluation.scoring.SampleScore;
import java.util.List;

/** 一个变体在单条样本上的一次主评分与可选三次稳定性输出。 */
public record EvaluationVariantResult(String variantId, SampleScore score, List<EvaluationAttemptResult> attempts) {}
