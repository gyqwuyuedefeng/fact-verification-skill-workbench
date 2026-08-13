package com.hsmap.factverification.skill;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Skill 编辑器提交的完整 DRAFT 内容。 */
public record UpdateDraftCommand(
        @NotBlank String skillMarkdown,
        @NotNull List<@Valid SkillReference> references,
        @NotBlank @Size(max = 2000) String changeSummary) {

    public SkillDraftContent toContent() {
        return new SkillDraftContent(skillMarkdown, references, changeSummary);
    }
}
