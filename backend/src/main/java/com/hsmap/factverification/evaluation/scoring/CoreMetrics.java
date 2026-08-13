package com.hsmap.factverification.evaluation.scoring;

import java.util.List;

/** 赛题明确要求的四项核心指标，不额外发明综合分。 */
public record CoreMetrics(
        MetricValue accuracy, MetricValue completionRate, MetricValue stability, MetricValue humanInterventionRate) {

    /** 从逐样本评分和三次稳定性观察计算可复现指标。 */
    public static CoreMetrics calculate(
            List<SampleScore> sampleScores, List<StabilityObservation> stabilityObservations) {
        List<SampleScore> scores = sampleScores == null ? List.of() : sampleScores;
        List<StabilityObservation> observations = stabilityObservations == null ? List.of() : stabilityObservations;
        return new CoreMetrics(
                MetricValue.of(scores.stream().filter(SampleScore::accurate).count(), scores.size()),
                MetricValue.of(scores.stream().filter(SampleScore::completed).count(), scores.size()),
                MetricValue.of(
                        observations.stream()
                                .filter(StabilityObservation::consistent)
                                .count(),
                        observations.size()),
                MetricValue.of(
                        scores.stream()
                                .filter(SampleScore::requiresHumanIntervention)
                                .count(),
                        scores.size()));
    }
}
