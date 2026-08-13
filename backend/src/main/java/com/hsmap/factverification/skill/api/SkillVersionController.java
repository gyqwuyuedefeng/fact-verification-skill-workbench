package com.hsmap.factverification.skill.api;

import com.hsmap.factverification.shared.RequestId;
import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.skill.CreateDraftCommand;
import com.hsmap.factverification.skill.SkillDraftView;
import com.hsmap.factverification.skill.SkillVersionComparisonService;
import com.hsmap.factverification.skill.SkillVersionContentView;
import com.hsmap.factverification.skill.SkillVersionService;
import com.hsmap.factverification.skill.SkillVersionView;
import com.hsmap.factverification.skill.UpdateDraftCommand;
import com.hsmap.factverification.skill.VersionCardService;
import com.hsmap.factverification.skill.VersionCardView;
import com.hsmap.factverification.skill.VersionComparison;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 单一 Skill 家族的编辑、冻结与版本卡 API。 */
@RestController
@RequestMapping("/api/skills/{skillKey}")
public class SkillVersionController {

    private final SkillVersionService versions;
    private final VersionCardService cards;
    private final SkillVersionComparisonService comparisons;

    public SkillVersionController(
            SkillVersionService versions, VersionCardService cards, SkillVersionComparisonService comparisons) {
        this.versions = versions;
        this.cards = cards;
        this.comparisons = comparisons;
    }

    @PostMapping("/drafts")
    public ResponseEntity<SkillVersionView> createDraft(
            @PathVariable String skillKey,
            @RequestHeader("Idempotency-Key") String requestId,
            @Valid @RequestBody CreateDraftCommand command) {
        requireSkillKey(skillKey);
        RequestId.requireValid(requestId);
        return ResponseEntity.status(201)
                .body(versions.createDraft(command.parentVersionId(), command.changeSummary()));
    }

    @PutMapping("/drafts/{draftId}")
    public SkillVersionView updateDraft(
            @PathVariable String skillKey,
            @PathVariable UUID draftId,
            @RequestHeader("Idempotency-Key") String requestId,
            @Valid @RequestBody UpdateDraftCommand command) {
        requireSkillKey(skillKey);
        RequestId.requireValid(requestId);
        return versions.updateDraft(draftId, command.toContent());
    }

    /** 返回可编辑草稿正文，避免管理员创建或刷新页面后得到一个空编辑器。 */
    @GetMapping("/drafts/{draftId}")
    public SkillDraftView draft(@PathVariable String skillKey, @PathVariable UUID draftId) {
        requireSkillKey(skillKey);
        return versions.getDraft(draftId);
    }

    /**
     * 删除管理员已确认放弃的独立 DRAFT。
     *
     * <p>控制器只暴露草稿路径并校验幂等键；服务层会依据数据库真实状态阻止冻结版本或已被子版本引用的草稿被删除。
     *
     * @param skillKey 工作台唯一 Skill 标识
     * @param draftId 待删除草稿标识
     * @param requestId 调用方生成的幂等请求标识
     * @return 204，删除后不返回已经失效的版本正文
     */
    @DeleteMapping("/drafts/{draftId}")
    public ResponseEntity<Void> deleteDraft(
            @PathVariable String skillKey,
            @PathVariable UUID draftId,
            @RequestHeader("Idempotency-Key") String requestId) {
        requireSkillKey(skillKey);
        RequestId.requireValid(requestId);
        versions.deleteDraft(draftId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/drafts/{draftId}/freeze")
    public ResponseEntity<SkillVersionView> freeze(
            @PathVariable String skillKey,
            @PathVariable UUID draftId,
            @RequestHeader("Idempotency-Key") String requestId) {
        requireSkillKey(skillKey);
        RequestId.requireValid(requestId);
        return ResponseEntity.status(201).body(versions.freeze(draftId));
    }

    @GetMapping("/versions")
    public List<SkillVersionView> list(@PathVariable String skillKey) {
        requireSkillKey(skillKey);
        return versions.list();
    }

    /**
     * 读取 DRAFT 或任一冻结历史版本的完整正文和 references。
     *
     * <p>统一内容接口解决“只有克隆后才能看到 SKILL.md”的问题；返回的 editable 仅用于页面呈现，实际更新权限继续由草稿更新接口校验。
     *
     * @param skillKey 工作台唯一 Skill 标识
     * @param versionId 待查看版本标识
     * @return 版本内容及服务端计算的可编辑性
     */
    @GetMapping("/versions/{versionId}/content")
    public SkillVersionContentView content(@PathVariable String skillKey, @PathVariable UUID versionId) {
        requireSkillKey(skillKey);
        return versions.getContent(versionId);
    }

    @GetMapping("/versions/{versionId}/card")
    public VersionCardView card(@PathVariable String skillKey, @PathVariable UUID versionId) {
        requireSkillKey(skillKey);
        return cards.get(versionId);
    }

    /** 手动触发版本审核辅助，不保存摘要，也不改变任何版本状态。 */
    @PostMapping("/versions/{versionId}/comparison")
    public VersionComparison compare(
            @PathVariable String skillKey,
            @PathVariable UUID versionId,
            @Valid @RequestBody CompareVersionRequest request) {
        requireSkillKey(skillKey);
        return comparisons.compare(versionId, request.baseVersionId());
    }

    public record CompareVersionRequest(@jakarta.validation.constraints.NotNull UUID baseVersionId) {}

    private static void requireSkillKey(String value) {
        if (!SkillVersionService.SKILL_KEY.equals(value)) {
            throw new ServiceException("SKILL_KEY_INVALID", "本工作台只管理固定事实核验 Skill");
        }
    }
}
