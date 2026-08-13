package com.hsmap.factverification.release;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 当前发布状态或一条追加历史。 */
public record ReleaseStateView(
        long revision,
        UUID stableVersionId,
        UUID candidateVersionId,
        UUID previousStableVersionId,
        boolean shadowEnabled,
        String action,
        String reason,
        OffsetDateTime createdAt) {}
