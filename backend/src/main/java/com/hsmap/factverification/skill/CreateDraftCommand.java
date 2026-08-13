package com.hsmap.factverification.skill;

import jakarta.validation.constraints.Size;
import java.util.UUID;

/** 可从现有冻结版本克隆，也可从仓库首个 Skill 源创建。 */
public record CreateDraftCommand(UUID parentVersionId, @Size(max = 2000) String changeSummary) {}
