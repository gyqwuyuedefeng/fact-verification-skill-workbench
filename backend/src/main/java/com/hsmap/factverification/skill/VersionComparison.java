package com.hsmap.factverification.skill;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 两个冻结 Skill 版本之间的审核辅助结果。
 *
 * <p>确定性差异仍是审核事实底座；模型摘要仅作为可失败的解释。完整结果会按目标版本和基础版本组合持久化，不能改变 Skill 内容、版本卡或发布门禁。
 */
public record VersionComparison(
        UUID targetVersionId,
        UUID baseVersionId,
        String baseContentHash,
        String targetContentHash,
        String deterministicDiff,
        String summaryStatus,
        GeneratedChangeSummary generatedSummary,
        String advisory,
        String errorCode,
        String modelId,
        OffsetDateTime generatedAt,
        boolean persisted) {}
