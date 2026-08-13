package com.hsmap.factverification.skill;

/** 把两份冻结 Skill 内容交给公司千问；失败由上层降级为纯确定性差异。 */
@FunctionalInterface
public interface SkillChangeSummaryClient {

    GeneratedChangeSummary summarize(String baseContent, String targetContent);
}
