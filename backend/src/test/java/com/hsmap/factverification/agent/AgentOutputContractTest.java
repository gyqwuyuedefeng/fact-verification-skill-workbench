package com.hsmap.factverification.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 被测试对象：发送给公司千问的统一核验输出契约提示。
 * 测试目的：保证普通任务和评测任务都能向通用模型明确传达可持久化 JSON 结构，而不是只引用模型不可见的 schema。
 * 覆盖范围：顶层字段、主张字段、规范指标词表、三种状态、工具失败降级和禁止复制文档 blocks 的约束。
 * 前置条件：真正的 JSON Schema 仍是最终失败关闭门禁，本提示只负责让模型知道应生成什么。
 */
class AgentOutputContractTest {

    /**
     * 测试场景：获取注入 BASELINE 与 Skill 的紧凑输出说明。
     * 前置条件：模型只能看到提示文字，不能读取后端 classpath 中的 schema 文件。
     * 期望结果：说明包含所有必填结构和失败降级规则，并明确拒绝 blocks 顶层输出。
     * 断言重点：提示必须足以区分输入快照结构和最终核验结果结构。
     */
    @Test
    void exposesCompletePersistableResultShapeToTheModel() {
        String instruction = AgentOutputContract.instruction();

        assertThat(instruction)
                .contains("runId", "variant", "documentSnapshotHash", "evidenceSnapshotId", "claims")
                .contains(
                        "claimId",
                        "claimText",
                        "materialLocator",
                        "normalizedClaim",
                        "subject",
                        "status",
                        "riskFlags",
                        "evidence",
                        "explanation",
                        "requiresHumanIntervention")
                .contains("VERIFIED", "CONFLICT", "INSUFFICIENT")
                .contains("工具失败")
                .contains("必须实际调用 resolve_company")
                .contains("未调用工具时禁止声称工具不可用、查询失败或没有记录")
                .contains("content 必须是 JSON 对象，禁止输出概括文字字符串")
                .contains("查询结果为空或某期间未命中，不能证明“不存在”")
                .contains("source 必须固定为 HS_ENTERPRISE_ES")
                .contains("禁止把文档快照的 blocks")
                .doesNotContain("expectedStatus", "manualEvidence");
    }

    /**
     * 测试场景：同一事实可能被模型表述为中文指标名、英文缩写或近义词。
     * 前置条件：评分器按金标的 metric 精确比较，BASELINE 与 Skill 必须共享同一词表而不能各自猜测。
     * 期望结果：输出契约固定本期八个指标值，并要求模型逐字复制其中一个值。
     * 断言重点：词表覆盖正式三十条数据中的全部指标，且不泄漏任何样本期望状态。
     */
    @Test
    void fixesCanonicalMetricVocabularyForReproducibleScoring() {
        assertThat(AgentOutputContract.instruction())
                .contains(
                        "unifiedSocialCreditCode",
                        "revenue",
                        "intellectualProperty",
                        "riskRecordAbsence",
                        "enterpriseRelationship",
                        "administrativePenalty",
                        "registeredAddress",
                        "legalRepresentative")
                .contains("必须逐字使用")
                .doesNotContain("expectedStatus", "manualEvidence");
    }

    /**
     * 测试场景：模型为了解释“存在至少一条”或单位换算，擅自改写材料主张的归一化五元组。
     * 前置条件：证据值可以用于比较，但 normalizedClaim 仍必须表达材料本身而不是工具返回值。
     * 期望结果：统一提示明确禁止语义等价改写，并固定存在性、否定性和金额主张的表达边界。
     * 断言重点：BASELINE 与所有 Skill 共享同一规则，避免评分差异来自输出口径漂移。
     */
    @Test
    void preservesTheMaterialClaimInsteadOfRewritingItFromEvidence() {
        assertThat(AgentOutputContract.instruction())
                .contains("归一化主张只描述材料原文")
                .contains("不得改写 operator、value 或 unit")
                .contains("存在至少一条")
                .contains("operator=EXISTS、value=true、unit=null")
                .contains("证据单位换算只用于比较")
                .doesNotContain("expectedStatus", "manualEvidence");
    }
}
