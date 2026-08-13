package com.hsmap.factverification.skill;

import java.util.UUID;

/** 按需生成的审核辅助结果，不写入 Skill、版本卡或发布门禁。 */
public record VersionComparison(
        UUID targetVersionId,
        UUID baseVersionId,
        String deterministicDiff,
        String summaryStatus,
        GeneratedChangeSummary generatedSummary,
        String advisory,
        String errorCode) {}
