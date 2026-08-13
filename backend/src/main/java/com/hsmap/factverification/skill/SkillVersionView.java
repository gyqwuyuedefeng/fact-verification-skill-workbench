package com.hsmap.factverification.skill;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Skill 生命周期页面需要的版本概要。 */
public record SkillVersionView(
        UUID id,
        String skillKey,
        String version,
        UUID parentVersionId,
        String status,
        String contentHash,
        String changeSummary,
        OffsetDateTime createdAt,
        OffsetDateTime frozenAt) {}
