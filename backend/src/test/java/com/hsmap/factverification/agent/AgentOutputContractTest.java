package com.hsmap.factverification.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 被测试对象：发送给公司千问的统一核验输出契约提示。
 * 测试目的：保证普通任务和评测任务都能向通用模型明确传达可持久化 JSON 结构，而不是只引用模型不可见的 schema。
 * 覆盖范围：顶层字段、主张字段、最小指标枚举、三种状态、工具失败降级和禁止复制文档 blocks 的约束。
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
                .contains("source 必须固定为 HS_ENTERPRISE_ES")
                .contains("禁止把文档快照的 blocks")
                // 空结果、期间覆盖等企业核验知识由 Skill 提供，不能泄漏给 BASELINE。
                .doesNotContain("查询结果为空或某期间未命中，不能证明“不存在”")
                .doesNotContain("expectedStatus", "manualEvidence");
    }

    /**
     * 测试场景：同一事实可能被模型表述为中文指标名、英文缩写或近义词。
     * 前置条件：评分器按金标的 metric 精确比较，BASELINE 与 Skill 必须共享同一词表而不能各自猜测。
     * 期望结果：输出契约固定本期八个结构枚举，但不注入企业核验中的归一化诀窍。
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
                .contains("必须使用下列固定枚举之一")
                .doesNotContain("禁止翻译成中文或自造近义词")
                .doesNotContain("expectedStatus", "manualEvidence");
    }

    /**
     * 测试场景：统一输出契约被误写入存在性、否定性或金额换算等领域诀窍。
     * 前置条件：BASELINE 与 Skill 只共享持久化 JSON 结构，企业事实归一化能力必须由冻结 Skill 提供。
     * 期望结果：公共提示不出现可直接提高核验分数的领域规则。
     * 断言重点：三版本差异确实来自 Skill，而不是 BASELINE 提前获得了优化 Skill 的知识。
     */
    @Test
    void keepsDomainNormalizationRulesInsideTheSkill() {
        assertThat(AgentOutputContract.instruction())
                .doesNotContain("归一化主张只描述材料原文")
                .doesNotContain("不得改写 operator、value 或 unit")
                .doesNotContain("存在至少一条")
                .doesNotContain("operator=EXISTS、value=true、unit=null")
                .doesNotContain("证据单位换算只用于比较")
                .doesNotContain("expectedStatus", "manualEvidence");
    }
}
