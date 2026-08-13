package com.hsmap.factverification.evaluation;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 评测 API 概要；大体积单样本结果通过独立路径获取。 */
public record EvaluationRunView(
        UUID id,
        String datasetVersion,
        String datasetHash,
        int sampleCount,
        Object variants,
        Object runManifest,
        Object metrics,
        String status,
        String gateStatus,
        Object gateReasons,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt) {

    /** 用于创建接口立即返回的最小 PENDING 视图。 */
    public static EvaluationRunView pending(UUID id, String datasetVersion, int sampleCount) {
        return new EvaluationRunView(
                id, datasetVersion, null, sampleCount, null, null, null, "PENDING", "PENDING", null, null, null);
    }
}
