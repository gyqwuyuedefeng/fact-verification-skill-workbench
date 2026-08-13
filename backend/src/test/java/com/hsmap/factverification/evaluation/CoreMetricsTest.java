package com.hsmap.factverification.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.hsmap.factverification.evaluation.scoring.CoreMetrics;
import com.hsmap.factverification.evaluation.scoring.MetricValue;
import com.hsmap.factverification.evaluation.scoring.SampleScore;
import com.hsmap.factverification.evaluation.scoring.StabilityObservation;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 以表驱动样本锁定赛题四个核心指标的分子、分母和值。 */
class CoreMetricsTest {

    /** 四项指标必须分别使用规格定义的样本判定，不用模糊综合分替代。 */
    @Test
    void calculatesFourContestMetrics() {
        List<SampleScore> scores = List.of(
                new SampleScore("s1", true, true, false),
                new SampleScore("s2", false, true, true),
                new SampleScore("s3", true, false, true));
        List<StabilityObservation> stability = List.of(
                new StabilityObservation("s1", List.of("C1:VERIFIED", "C1:VERIFIED", "C1:VERIFIED")),
                new StabilityObservation("s2", List.of("C2:CONFLICT", "C2:VERIFIED", "C2:CONFLICT")));

        CoreMetrics result = CoreMetrics.calculate(scores, stability);

        assertMetric(result.accuracy(), 2, 3, 2.0 / 3.0);
        assertMetric(result.completionRate(), 2, 3, 2.0 / 3.0);
        assertMetric(result.stability(), 1, 2, 0.5);
        assertMetric(result.humanInterventionRate(), 2, 3, 2.0 / 3.0);
    }

    /** 空评测不能产生 NaN/Infinity；门禁会另行拒绝少于 30 条。 */
    @Test
    void protectsAllZeroDenominators() {
        CoreMetrics result = CoreMetrics.calculate(List.of(), List.of());

        assertThat(result.accuracy().value()).isZero();
        assertThat(result.completionRate().value()).isZero();
        assertThat(result.stability().value()).isZero();
        assertThat(result.humanInterventionRate().value()).isZero();
    }

    private static void assertMetric(MetricValue metric, long numerator, long denominator, double value) {
        assertThat(metric.numerator()).isEqualTo(numerator);
        assertThat(metric.denominator()).isEqualTo(denominator);
        assertThat(metric.value()).isEqualTo(value);
    }
}
