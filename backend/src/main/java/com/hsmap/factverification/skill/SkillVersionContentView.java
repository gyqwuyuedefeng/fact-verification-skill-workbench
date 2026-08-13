package com.hsmap.factverification.skill;

import java.util.List;
import java.util.UUID;

/**
 * 管理端查看任意 Skill 版本时使用的完整内容投影。
 *
 * <p>该投影同时覆盖 DRAFT、CANDIDATE、STABLE 和 ARCHIVED，避免前端为了查看冻结版本而错误调用仅限草稿的编辑接口。
 * {@code editable} 是服务端根据真实持久化状态计算的展示提示；真正的不可变边界仍由更新接口的 {@code status = DRAFT} 条件保证。
 *
 * @param id 版本唯一标识
 * @param parentVersionId 克隆来源；初始版本可以为空
 * @param status 当前生命周期状态
 * @param editable 是否允许通过草稿更新接口修改
 * @param skillMarkdown 该版本完整的 SKILL.md
 * @param references 冻结或草稿保存的参考文件快照
 * @param changeSummary 人工填写的版本变更说明
 */
public record SkillVersionContentView(
        UUID id,
        UUID parentVersionId,
        String status,
        boolean editable,
        String skillMarkdown,
        List<SkillReference> references,
        String changeSummary) {

    /**
     * 将 references 复制为不可变列表，防止控制器序列化期间或调用方展示逻辑意外改写仓储快照。
     */
    public SkillVersionContentView {
        references = List.copyOf(references);
    }
}
