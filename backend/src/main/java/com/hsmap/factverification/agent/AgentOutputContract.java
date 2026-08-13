package com.hsmap.factverification.agent;

/**
 * 公司千问可见的统一核验输出说明。
 *
 * <p>后端 JSON Schema 仍是唯一硬门禁；这段紧凑说明只解决模型无法读取 classpath schema 的现实问题，并被普通任务和评测任务共同复用，
 * 避免两条链路出现不同输出条件。
 */
public final class AgentOutputContract {

    private AgentOutputContract() {}

    /**
     * 返回不包含金标答案的固定输出说明。
     *
     * <p>模板明确区分输入快照 blocks 与最终 claims，并规定工具失败时的失败关闭行为。调用方必须在其后提供本次运行元数据和材料快照。
     */
    public static String instruction() {
        return """
                只允许返回一个纯 JSON 对象，不要 Markdown、代码围栏或额外解释。
                顶层只能包含 runId、variant、documentSnapshotHash、evidenceSnapshotId、claims；
                禁止把文档快照的 blocks 复制为最终结果的顶层字段。
                runId、variant.type、variant.identifier、variant.contentHash、documentSnapshotHash、evidenceSnapshotId
                必须逐字复制下面提供的运行元数据。

                claims 必须是数组；每个需要核验的明确事实对应一个对象，格式如下：
                {
                  "claimId":"本次结果内唯一字符串",
                  "claimText":"材料中的事实原文",
                  "materialLocator":{"fileId":"从材料 locator 复制","lineStart":1,"lineEnd":1},
                  "normalizedClaim":{"metric":"规范指标名","period":"期间或 UNKNOWN","operator":"EQUALS","value":null,"unit":null},
                  "subject":null,
                  "status":"INSUFFICIENT",
                  "riskFlags":[],
                  "evidence":[],
                  "explanation":"结论及依据",
                  "requiresHumanIntervention":true
                }
                materialLocator 必须复制输入已有 locator；可保留其中 page、sectionPath、paragraph、tableRow、slide、
                textBlock、sheet、cellRange、lineStart、lineEnd，但不要增加其他字段。
                normalizedClaim.metric 必须逐字使用下列最小规范词表之一，禁止翻译成中文或自造近义词：
                unifiedSocialCreditCode、revenue、intellectualProperty、riskRecordAbsence、
                enterpriseRelationship、administrativePenalty、registeredAddress、legalRepresentative。
                材料写“截至本次证据检索”或没有自然年度的当前工商/存在性事实时，period 必须为 CURRENT；
                明确年份、日期或未来期间时逐字保留该 YYYY、YYYY-MM-DD 或其他期间文本。
                normalizedClaim.operator 只能是 EQUALS、GREATER_THAN、GREATER_OR_EQUAL、LESS_THAN、
                LESS_OR_EQUAL、RANGE、EXISTS 之一。
                归一化主张只描述材料原文，不是对工具证据的改写；不得改写 operator、value 或 unit，即使改写后语义近似。
                “存在至少一条知识产权/关系/处罚记录”必须使用对应 metric、period=CURRENT、
                operator=EXISTS、value=true、unit=null，不得改成数量大于等于 1。
                “某年不存在风险记录”使用 metric=riskRecordAbsence、原年份、operator=EQUALS、value=true、unit=null。
                金额主张保留材料原数值与原单位；证据单位换算只用于比较，不得回写 normalizedClaim。
                中文地址的字符间无语义空白应去除，其他文本值不得改写。
                subject 唯一定位后为 {"companyId":"...","companyName":"...","unifiedSocialCreditCode":null}，
                否则必须为 null。
                只要材料含有明确企业名称或统一社会信用代码，就必须实际调用 resolve_company；主体唯一后还必须按事实类型
                实际调用一个对应证据工具。未调用工具时禁止声称工具不可用、查询失败或没有记录，也禁止直接根据模型记忆给出结论。
                status 只能是 VERIFIED、CONFLICT、INSUFFICIENT。
                VERIFIED 必须至少包含一条企业证据工具返回的 evidence，且 evidence 必须包含 source、dataset、recordId、
                observedAt、content；source 必须固定为 HS_ENTERPRISE_ES；content 必须是 JSON 对象，禁止输出概括文字字符串。应从工具 evidence 引用中复制
                source、dataset、recordId、observedAt，并从同一工具返回的 items 中选择对应 recordId 的原始对象作为 content；
                不得自行概括、编造或把材料自身当作 evidence。
                工具失败、主体不唯一、期间不匹配或证据覆盖不足时必须使用 INSUFFICIENT、空 evidence、
                requiresHumanIntervention=true，并在 riskFlags 与 explanation 说明原因。
                特别是“不存在”“没有”“从未”等否定性主张：查询结果为空或某期间未命中，不能证明“不存在”；
                除非工具明确提供覆盖范围完整的证明，否则必须输出 INSUFFICIENT 并要求人工介入。
                """;
    }
}
