package com.hsmap.factverification.agent;

import java.nio.file.Path;

/** 同条件评测中的唯一变化：通用基线指令或一个冻结 Skill。 */
public record AgentVariant(
        String type, String identifier, String contentHash, String systemPrompt, Path skillRuntimeRoot) {

    /** 作为评测资产冻结的通用基线指令；它不得加载专用 Skill。 */
    public static final String BASELINE_INSTRUCTION = "读取文档快照，使用可用企业证据工具核验明确事实，并严格按输出 JSON schema 返回。无法确认时输出证据不足。";

    /** 公司千问通用基线不加载专用 Skill。 */
    public static AgentVariant baseline(String contentHash) {
        return new AgentVariant("BASELINE", "BASELINE", contentHash, BASELINE_INSTRUCTION, null);
    }

    /** 已冻结 Skill 变体必须提供只读运行时父目录。 */
    public static AgentVariant skill(String identifier, String contentHash, Path runtimeRoot) {
        return new AgentVariant(
                "SKILL",
                identifier,
                contentHash,
                "执行企业材料事实核验。必须遵守下面注入的已冻结 company-material-fact-check Skill 证据规则，并严格按输出 JSON schema 返回。",
                runtimeRoot);
    }
}
