package com.hsmap.factverification.skill;

import java.util.List;
import java.util.UUID;

/** 管理员编辑器需要的 DRAFT 内容；冻结版本不通过此投影暴露为可编辑对象。 */
public record SkillDraftView(
        UUID id,
        UUID parentVersionId,
        String skillMarkdown,
        List<SkillReference> references,
        String changeSummary) {

    /** 防止调用方修改仓储反序列化得到的 references 列表。 */
    public SkillDraftView {
        references = List.copyOf(references);
    }
}
