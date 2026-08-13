package com.hsmap.factverification.evaluation.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.hsmap.factverification.evaluation.dataset.GoldSample;
import java.math.BigDecimal;

/** 按统一金标口径为一个变体的一条样本评分。 */
public final class GoldScorer {

    /**
     * 准确要求主体、结论以及结论所需的核心证据同时成立。
     *
     * <p>VERIFIED/CONFLICT 必须包含外部证据；INSUFFICIENT 允许空证据。格式不合法或漏抽取直接按错误处理。
     */
    public SampleScore score(GoldSample gold, JsonNode result, boolean completedWithinTimeout) {
        JsonNode claim = firstClaim(result);
        boolean legalResult = claim != null && claim.isObject();
        boolean subjectCorrect = legalResult
                && text(gold.expectedSubject(), "companyId").equals(text(claim.path("subject"), "companyId"));
        boolean statusCorrect = legalResult && gold.expectedStatus().equals(text(claim, "status"));
        boolean normalizedClaimCorrect =
                legalResult && normalizedClaimMatches(gold.normalizedClaim(), claim.path("normalizedClaim"));
        boolean coreEvidenceCorrect = legalResult
                && ("INSUFFICIENT".equals(gold.expectedStatus())
                        || (claim.path("evidence").isArray()
                                && !claim.path("evidence").isEmpty()));
        boolean accurate = subjectCorrect && normalizedClaimCorrect && statusCorrect && coreEvidenceCorrect;
        boolean requestedIntervention =
                legalResult && claim.path("requiresHumanIntervention").asBoolean(false);
        return new SampleScore(
                gold.sampleId(), accurate, completedWithinTimeout && legalResult, requestedIntervention || !accurate);
    }

    private static JsonNode firstClaim(JsonNode result) {
        if (result == null
                || !result.path("claims").isArray()
                || result.path("claims").isEmpty()) {
            return null;
        }
        return result.path("claims").get(0);
    }

    private static String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("");
    }

    /** 规范化主张必须保持材料中的指标、期间、操作符、值和单位，不能只猜中最终状态。 */
    private static boolean normalizedClaimMatches(JsonNode expected, JsonNode actual) {
        if (expected == null || actual == null || !actual.isObject()) {
            return false;
        }
        return text(expected, "metric").equals(text(actual, "metric"))
                && text(expected, "period").equals(text(actual, "period"))
                && text(expected, "operator").equals(text(actual, "operator"))
                && nullableText(expected.get("unit")).equals(nullableText(actual.get("unit")))
                && valueEquals(expected.get("value"), actual.get("value"));
    }

    /** 数字允许 JSON number/string 的表示差异，但不做未经金标声明的近似或单位换算。 */
    private static boolean valueEquals(JsonNode expected, JsonNode actual) {
        if (expected == null || actual == null || expected.isNull() || actual.isNull()) {
            return expected != null && actual != null && expected.isNull() && actual.isNull();
        }
        try {
            return new BigDecimal(expected.asText()).compareTo(new BigDecimal(actual.asText())) == 0;
        } catch (NumberFormatException ignored) {
            return expected.isBoolean() && actual.isBoolean()
                    ? expected.booleanValue() == actual.booleanValue()
                    : expected.asText().equals(actual.asText());
        }
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }
}
