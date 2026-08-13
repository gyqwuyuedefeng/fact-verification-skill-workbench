package com.hsmap.factverification.evaluation;

import com.hsmap.factverification.evaluation.dataset.GoldSample;
import java.util.Map;

/** 单条金标在所有变体下的对照结果。 */
public record EvaluationSampleResult(
        String sampleId, GoldSample gold, Map<String, EvaluationVariantResult> variantResults) {}
