package com.hsmap.factverification.skill;

import java.util.List;
import java.util.UUID;

/** 冻结版本的可审核摘要。 */
public record VersionCardView(
        String skillKey,
        String version,
        String status,
        String parentVersion,
        String contentHash,
        String changeSummary,
        UUID evaluationRunId,
        Object metrics,
        String gateStatus,
        List<String> knownFailures) {}
