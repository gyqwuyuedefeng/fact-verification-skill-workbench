package com.hsmap.factverification.evaluation.scoring;

/**
 * 单项评测指标。
 *
 * <p>同时保留分子和分母，避免报告只给百分比而无法复核；空数据集固定返回 0，禁止产生 NaN 或无穷大。
 */
public record MetricValue(long numerator, long denominator, double value) {

    /** 根据可复核的计数创建指标值。 */
    public static MetricValue of(long numerator, long denominator) {
        double value = denominator == 0 ? 0.0 : (double) numerator / denominator;
        return new MetricValue(numerator, denominator, value);
    }
}
