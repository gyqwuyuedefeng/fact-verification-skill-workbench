package com.hsmap.factverification.skill;

import java.util.List;

/** DRAFT 编辑允许变更的三个字段。 */
public record SkillDraftContent(String skillMarkdown, List<SkillReference> references, String changeSummary) {}
