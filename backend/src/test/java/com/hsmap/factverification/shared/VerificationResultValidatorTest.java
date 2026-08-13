package com.hsmap.factverification.shared;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 被测试对象：{@link VerificationResultValidator} 统一结果门禁。
 * 测试目的：保证 JSON Schema 与 VERIFIED 证据不变式失败关闭，同时为真实模型评测提供不泄露原文的结构诊断。
 * 覆盖范围：合法 VERIFIED、缺失证据以及 schema 多余字段的拒绝路径。
 * 关键前置条件：使用正式 classpath schema，诊断中只允许保留实例 JSON 路径和 schema 关键字。
 */
class VerificationResultValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VerificationResultValidator validator =
            new VerificationResultValidator(objectMapper, "schemas/verification-result.schema.json");

    /**
     * 测试场景：结果完整符合统一输出契约。
     * 前置条件：VERIFIED 主张同时具有材料位置和一条企业外部证据。
     * 期望结果：门禁不抛出任何异常。
     * 断言重点：合法结果不能被诊断增强逻辑误拒绝。
     */
    @Test
    void acceptsValidVerifiedClaim() throws Exception {
        assertThatCode(() -> validator.validate(resultWithEvidence(true))).doesNotThrowAnyException();
    }

    /**
     * 测试场景：VERIFIED 主张没有任何外部证据。
     * 前置条件：其他运行元数据和主张字段都合法，仅 evidence 为空。
     * 期望结果：按 schema 失败关闭，不得持久化为正式结果。
     * 断言重点：模型解释不能替代可追溯的企业证据。
     */
    @Test
    void rejectsVerifiedClaimWithoutExternalEvidence() throws Exception {
        assertThatThrownBy(() -> validator.validate(resultWithEvidence(false)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("RESULT_SCHEMA_INVALID");
    }

    /**
     * 测试场景：模型在单条主张中输出 schema 之外的字段。
     * 前置条件：基础结果本身合法，只在 claims[0] 加入一个不被契约允许的字段。
     * 期望结果：异常保留失败的 JSON 路径和 additionalProperties 关键字。
     * 断言重点：诊断足以区分结构问题，但不需要保存或回显模型原文。
     */
    @Test
    void reportsSanitizedSchemaPathForInvalidResult() throws Exception {
        JsonNode invalid = resultWithEvidence(true).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("claims").get(0))
                .put("unexpectedModelField", "这段模型原文不应进入诊断");

        assertThatThrownBy(() -> validator.validate(invalid))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("/claims/0")
                .hasMessageContaining("additionalProperties")
                .hasMessageNotContaining("这段模型原文不应进入诊断");
    }

    private JsonNode resultWithEvidence(boolean withEvidence) throws Exception {
        String evidence = withEvidence
                ? "[{\"source\":\"HS_ENTERPRISE_ES\",\"dataset\":\"company_info\",\"recordId\":\"1\",\"observedAt\":\"2026-08-12T00:00:00Z\",\"content\":{\"status\":\"存续\"}}]"
                : "[]";
        return objectMapper.readTree(
                """
                {
                  "runId":"b62d9778-31c4-477d-b651-707efcc15b44",
                  "variant":{"type":"SKILL","identifier":"v1","contentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                  "documentSnapshotHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "evidenceSnapshotId":"3827ebd8-c0c7-4b51-b190-4ef2f818c337",
                  "claims":[{
                    "claimId":"c1",
                    "claimText":"企业状态为存续",
                    "materialLocator":{"fileId":"f1","lineStart":1,"lineEnd":1},
                    "normalizedClaim":{"metric":"registration_status","period":"current","operator":"EQUALS","value":"存续","unit":null},
                    "subject":{"companyId":"1","companyName":"火石创造","unifiedSocialCreditCode":null},
                    "status":"VERIFIED",
                    "riskFlags":[],
                    "evidence":%s,
                    "explanation":"材料与企业事实库一致",
                    "requiresHumanIntervention":false
                  }]
                }
                """
                        .formatted(evidence));
    }
}
