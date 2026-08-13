package com.hsmap.factverification.skill;

/** 把两份冻结 Skill 内容交给公司千问；失败由上层降级为纯确定性差异。 */
@FunctionalInterface
public interface SkillChangeSummaryClient {

    GeneratedChangeSummary summarize(String baseContent, String targetContent);

    /**
     * 返回生成摘要所使用的模型稳定标识。
     *
     * <p>持久化结果需要记录模型来源，方便审核人在模型配置后续变化时辨别历史说明；默认值保证已有函数式测试替身仍可直接使用。
     *
     * @return 客户端可识别模型时返回其标识，否则返回 {@code unknown}
     */
    default String modelId() {
        return "unknown";
    }
}
