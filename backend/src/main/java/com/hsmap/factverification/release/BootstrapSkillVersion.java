package com.hsmap.factverification.release;

import java.util.UUID;

/** 初始发布门禁需要的最小冻结 Skill 投影。 */
public record BootstrapSkillVersion(UUID id, String status, String contentHash) {}
