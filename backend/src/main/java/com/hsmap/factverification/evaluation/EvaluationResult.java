package com.hsmap.factverification.evaluation;

import com.hsmap.factverification.evaluation.manifest.RunManifest;
import com.hsmap.factverification.evaluation.scoring.CoreMetrics;
import java.util.List;
import java.util.Map;

/** 完成一次同条件运行后的原始样本和四指标矩阵。 */
public record EvaluationResult(
        RunManifest manifest,
        List<EvaluationVariantSummary> variants,
        List<EvaluationSampleResult> sampleResults,
        Map<String, CoreMetrics> metrics) {}
