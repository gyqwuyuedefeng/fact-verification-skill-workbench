package com.hsmap.factverification.release;

import com.hsmap.factverification.evaluation.persistence.EvaluationRunRepository;
import com.hsmap.factverification.persistence.JdbcJson;
import com.hsmap.factverification.release.persistence.ReleaseBindingRepository;
import com.hsmap.factverification.run.persistence.VerificationRunRepository;
import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.skill.SkillVersionService;
import com.hsmap.factverification.skill.VersionCardService;
import com.hsmap.factverification.skill.VersionCardView;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单 Skill 家族的最小发布状态机：注册、影子启停、晋升和回滚。
 *
 * <p>所有操作只追加 release_binding 事件；当前状态由最新 revision 推导，不维护可覆盖指针表。
 */
@Service
public class ReleaseService {

    private static final String SKILL_KEY = SkillVersionService.SKILL_KEY;
    private static final String OPERATOR = "single-reviewer";

    private final ReleaseBindingRepository releases;
    private final SkillVersionRepository versions;
    private final EvaluationRunRepository evaluations;
    private final VerificationRunRepository runs;
    private final InitialStableBootstrapService initialStable;
    private final VersionCardService cards;
    private final JdbcJson jdbcJson;

    public ReleaseService(
            ReleaseBindingRepository releases,
            SkillVersionRepository versions,
            EvaluationRunRepository evaluations,
            VerificationRunRepository runs,
            InitialStableBootstrapService initialStable,
            VersionCardService cards,
            JdbcJson jdbcJson) {
        this.releases = releases;
        this.versions = versions;
        this.evaluations = evaluations;
        this.runs = runs;
        this.initialStable = initialStable;
        this.cards = cards;
        this.jdbcJson = jdbcJson;
    }

    /** 无 Stable 时建立初始 Stable；已有 Stable 时注册唯一 Candidate。 */
    @Transactional
    public ReleaseStateView register(UUID candidateVersionId, UUID evaluationId, String reason) {
        requireReason(reason);
        SkillVersionRepository.VersionRow candidate = candidate(candidateVersionId);
        ReleaseBindingRepository.ReleaseState current =
                releases.findLatestForUpdate(SKILL_KEY).orElse(null);
        if (current != null && current.candidateVersionId() != null) {
            throw new ServiceException("CANDIDATE_ALREADY_ACTIVE", "当前已有注册 Candidate");
        }
        BootstrapEvaluation evaluation = approvedEvaluation(
                candidateVersionId, evaluationId, current == null ? null : current.stableVersionId());
        VersionCardView card = cards.build(candidate, evaluationId);
        if (versions.registerCandidate(candidateVersionId, evaluationId, jdbcJson.write(card)) != 1) {
            throw new ServiceException("CANDIDATE_ALREADY_REGISTERED", "Candidate 已注册或状态已变化");
        }

        if (current == null) {
            initialStable.initialize(candidateVersionId, evaluationId, reason, OPERATOR);
            return new ReleaseStateView(
                    1, candidateVersionId, null, null, false, "INITIALIZE", reason, OffsetDateTime.now(ZoneOffset.UTC));
        }
        return append(
                current,
                "REGISTER",
                current.stableVersionId(),
                candidateVersionId,
                current.previousStableVersionId(),
                false,
                evaluation.id(),
                reason);
    }

    /** 开启真实任务影子运行，不做用户比例分流。 */
    @Transactional
    public ReleaseStateView startShadow(String reason) {
        requireReason(reason);
        ReleaseBindingRepository.ReleaseState current = lockCurrent();
        if (current.candidateVersionId() == null) {
            throw new ServiceException("CANDIDATE_NOT_REGISTERED", "没有可灰度 Candidate");
        }
        if (current.shadowEnabled()) {
            return view(current);
        }
        return append(
                current,
                "SHADOW_START",
                current.stableVersionId(),
                current.candidateVersionId(),
                current.previousStableVersionId(),
                true,
                current.evaluationRunId(),
                reason);
    }

    /** 停止新任务创建 SHADOW，不影响已开始运行和历史结果。 */
    @Transactional
    public ReleaseStateView stopShadow(String reason) {
        requireReason(reason);
        ReleaseBindingRepository.ReleaseState current = lockCurrent();
        if (!current.shadowEnabled()) {
            return view(current);
        }
        return append(
                current,
                "SHADOW_STOP",
                current.stableVersionId(),
                current.candidateVersionId(),
                current.previousStableVersionId(),
                false,
                current.evaluationRunId(),
                reason);
    }

    /** 离线门禁已在注册校验；晋升额外要求至少一条真实材料影子 PASS。 */
    @Transactional
    public ReleaseStateView promote(String reason) {
        requireReason(reason);
        ReleaseBindingRepository.ReleaseState current = lockCurrent();
        UUID candidateId = current.candidateVersionId();
        if (candidateId == null) {
            throw new ServiceException("CANDIDATE_NOT_REGISTERED", "没有可晋升 Candidate");
        }
        if (!runs.hasPassedShadow(candidateId)) {
            throw new ServiceException("SHADOW_PASS_REQUIRED", "Candidate 至少需要一条影子复核 PASS");
        }
        if (versions.markArchived(current.stableVersionId()) != 1 || versions.markStable(candidateId) != 1) {
            throw new ServiceException("PROMOTION_STATE_CHANGED", "版本状态已变化，请刷新后重试");
        }
        return append(
                current,
                "PROMOTE",
                candidateId,
                null,
                current.stableVersionId(),
                false,
                current.evaluationRunId(),
                reason);
    }

    /** 回滚只改变后续任务读取的 Stable；历史和运行中任务仍保留原版本 ID。 */
    @Transactional
    public ReleaseStateView rollback(String reason) {
        requireReason(reason);
        ReleaseBindingRepository.ReleaseState current = lockCurrent();
        UUID target = current.previousStableVersionId();
        if (target == null) {
            throw new ServiceException("ROLLBACK_TARGET_MISSING", "没有上一 Stable 可回滚");
        }
        if (versions.markArchived(current.stableVersionId()) != 1 || versions.restoreStable(target) != 1) {
            throw new ServiceException("ROLLBACK_STATE_CHANGED", "版本状态已变化，请刷新后重试");
        }
        return append(
                current, "ROLLBACK", target, null, current.stableVersionId(), false, current.evaluationRunId(), reason);
    }

    public ReleaseStateView current() {
        return releases.findLatest(SKILL_KEY)
                .map(ReleaseService::view)
                .orElseThrow(() -> new ServiceException("RELEASE_NOT_INITIALIZED", "尚未建立初始 Stable"));
    }

    public List<ReleaseStateView> history() {
        return releases.listHistory(SKILL_KEY).stream()
                .map(ReleaseService::view)
                .toList();
    }

    /**
     * 对已完成 SHADOW 追加一次人工结论。
     *
     * <p>复核只更新影子运行的专用字段；PRIMARY 结果、任务状态和发布绑定均不会被改写。
     */
    @Transactional
    public void reviewShadow(UUID runId, String status, String reason) {
        requireReason(reason);
        if (!"PASS".equals(status) && !"FAIL".equals(status)) {
            throw new ServiceException("SHADOW_REVIEW_STATUS_INVALID", "影子复核状态只能是 PASS 或 FAIL");
        }
        if (runs.reviewShadow(runId, status, reason, OPERATOR, OffsetDateTime.now(ZoneOffset.UTC)) != 1) {
            throw new ServiceException("SHADOW_REVIEW_NOT_ALLOWED", "影子运行不存在、尚未完成或已经复核");
        }
    }

    private SkillVersionRepository.VersionRow candidate(UUID id) {
        SkillVersionRepository.VersionRow row = versions.findVersion(id)
                .orElseThrow(() -> new ServiceException("SKILL_VERSION_NOT_FOUND", "Candidate 不存在"));
        if (!"CANDIDATE".equals(row.status()) || row.contentHash() == null) {
            throw new ServiceException("CANDIDATE_NOT_FROZEN", "只有冻结 Candidate 可以注册");
        }
        return row;
    }

    private BootstrapEvaluation approvedEvaluation(UUID candidateId, UUID evaluationId, UUID stableId) {
        BootstrapEvaluation evaluation = evaluations
                .findBootstrap(evaluationId)
                .orElseThrow(() -> new ServiceException("EVALUATION_NOT_FOUND", "注册评测不存在"));
        if (!"PASS".equals(evaluation.gateStatus())
                || !evaluation.variantIdentifiers().contains("BASELINE")
                || !evaluation.variantIdentifiers().contains(candidateId.toString())) {
            throw new ServiceException("CANDIDATE_GATE_FAILED", "Candidate 未通过包含 BASELINE 的同条件评测");
        }
        // 首版没有 Stable，可以只与 BASELINE 对照；从第二版开始必须把当前 Stable 放进同一次评测。
        if (stableId != null && !evaluation.variantIdentifiers().contains(stableId.toString())) {
            throw new ServiceException("EVALUATION_STABLE_MISMATCH", "注册评测未包含当前 Stable");
        }
        return evaluation;
    }

    private ReleaseBindingRepository.ReleaseState lockCurrent() {
        return releases.findLatestForUpdate(SKILL_KEY)
                .orElseThrow(() -> new ServiceException("RELEASE_NOT_INITIALIZED", "尚未建立初始 Stable"));
    }

    private ReleaseStateView append(
            ReleaseBindingRepository.ReleaseState before,
            String action,
            UUID stableId,
            UUID candidateId,
            UUID previousStableId,
            boolean shadowEnabled,
            UUID evaluationId,
            String reason) {
        requireReason(reason);
        long revision = ReleaseBindingRepository.nextRevision(before.revision());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String stateAfter = stateJson(stableId, candidateId, previousStableId, shadowEnabled);
        releases.append(new ReleaseBindingRepository.ReleaseEvent(
                UUID.randomUUID(),
                SKILL_KEY,
                revision,
                action,
                stableId,
                candidateId,
                previousStableId,
                shadowEnabled,
                evaluationId,
                before.stateAfterJson(),
                stateAfter,
                reason,
                OPERATOR,
                now));
        return new ReleaseStateView(
                revision, stableId, candidateId, previousStableId, shadowEnabled, action, reason, now);
    }

    private String stateJson(UUID stableId, UUID candidateId, UUID previousStableId, boolean shadowEnabled) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("stableVersionId", stableId);
        state.put("candidateVersionId", candidateId);
        state.put("previousStableVersionId", previousStableId);
        state.put("shadowEnabled", shadowEnabled);
        return jdbcJson.write(state);
    }

    private static ReleaseStateView view(ReleaseBindingRepository.ReleaseState state) {
        return new ReleaseStateView(
                state.revision(),
                state.stableVersionId(),
                state.candidateVersionId(),
                state.previousStableVersionId(),
                state.shadowEnabled(),
                state.action(),
                state.reason(),
                state.createdAt());
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new ServiceException("RELEASE_REASON_REQUIRED", "发布操作必须填写原因");
        }
    }
}
