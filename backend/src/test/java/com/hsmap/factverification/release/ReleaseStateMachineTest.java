package com.hsmap.factverification.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hsmap.factverification.evaluation.persistence.EvaluationRunRepository;
import com.hsmap.factverification.persistence.JdbcJson;
import com.hsmap.factverification.release.persistence.ReleaseBindingRepository;
import com.hsmap.factverification.run.persistence.VerificationRunRepository;
import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.skill.VersionCardService;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * 锁定单 Skill MVP 的发布状态机边界。
 *
 * <p>发布历史只能追加 revision；离线门禁负责注册，真实材料影子人工 PASS 负责最终晋升。
 */
class ReleaseStateMachineTest {

    private static final UUID STABLE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CANDIDATE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID EVALUATION_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");

    private ReleaseBindingRepository releases;
    private SkillVersionRepository versions;
    private VerificationRunRepository runs;
    private ReleaseService service;

    @BeforeEach
    void setUp() {
        releases = Mockito.mock(ReleaseBindingRepository.class);
        versions = Mockito.mock(SkillVersionRepository.class);
        runs = Mockito.mock(VerificationRunRepository.class);
        service = new ReleaseService(
                releases,
                versions,
                Mockito.mock(EvaluationRunRepository.class),
                runs,
                Mockito.mock(InitialStableBootstrapService.class),
                Mockito.mock(VersionCardService.class),
                Mockito.mock(JdbcJson.class));
    }

    /** 开启影子只追加下一 revision，不改变 Stable/Candidate 绑定。 */
    @Test
    void startsShadowByAppendingNextRevision() {
        when(releases.findLatestForUpdate("company-material-fact-check"))
                .thenReturn(Optional.of(state(7, false, STABLE_ID)));

        ReleaseStateView result = service.startShadow("开始真实材料影子验证");

        assertThat(result.revision()).isEqualTo(8);
        assertThat(result.stableVersionId()).isEqualTo(STABLE_ID);
        assertThat(result.candidateVersionId()).isEqualTo(CANDIDATE_ID);
        assertThat(result.shadowEnabled()).isTrue();
        ArgumentCaptor<ReleaseBindingRepository.ReleaseEvent> event =
                ArgumentCaptor.forClass(ReleaseBindingRepository.ReleaseEvent.class);
        verify(releases).append(event.capture());
        assertThat(event.getValue().action()).isEqualTo("SHADOW_START");
        assertThat(event.getValue().revision()).isEqualTo(8);
    }

    /** 没有人工通过的影子记录时，Candidate 不能成为正式 Stable。 */
    @Test
    void rejectsPromotionBeforeAnyShadowPass() {
        when(releases.findLatestForUpdate("company-material-fact-check"))
                .thenReturn(Optional.of(state(8, true, STABLE_ID)));
        when(runs.hasPassedShadow(CANDIDATE_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.promote("准备晋升"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("SHADOW_PASS_REQUIRED");

        verify(versions, never()).markArchived(any());
        verify(versions, never()).markStable(any());
        verify(releases, never()).append(any());
    }

    /** PASS 后晋升同时归档旧 Stable，并把旧版本保留为明确回滚目标。 */
    @Test
    void promotesPassedCandidateAndKeepsRollbackTarget() {
        when(releases.findLatestForUpdate("company-material-fact-check"))
                .thenReturn(Optional.of(state(8, true, STABLE_ID)));
        when(runs.hasPassedShadow(CANDIDATE_ID)).thenReturn(true);
        when(versions.markArchived(STABLE_ID)).thenReturn(1);
        when(versions.markStable(CANDIDATE_ID)).thenReturn(1);

        ReleaseStateView result = service.promote("影子结果人工复核通过");

        assertThat(result.stableVersionId()).isEqualTo(CANDIDATE_ID);
        assertThat(result.candidateVersionId()).isNull();
        assertThat(result.previousStableVersionId()).isEqualTo(STABLE_ID);
        assertThat(result.shadowEnabled()).isFalse();
        verify(versions).markArchived(STABLE_ID);
        verify(versions).markStable(CANDIDATE_ID);
    }

    /** 回滚只影响后续任务；当前 Stable 被归档，上一 Stable 恢复。 */
    @Test
    void rollsBackToPreviousStable() {
        ReleaseBindingRepository.ReleaseState promoted = new ReleaseBindingRepository.ReleaseState(
                9,
                "PROMOTE",
                CANDIDATE_ID,
                null,
                STABLE_ID,
                false,
                EVALUATION_ID,
                "{}",
                "已晋升",
                "single-reviewer",
                now());
        when(releases.findLatestForUpdate("company-material-fact-check")).thenReturn(Optional.of(promoted));
        when(versions.markArchived(CANDIDATE_ID)).thenReturn(1);
        when(versions.restoreStable(STABLE_ID)).thenReturn(1);

        ReleaseStateView result = service.rollback("线上人工检查需要回退");

        assertThat(result.revision()).isEqualTo(10);
        assertThat(result.stableVersionId()).isEqualTo(STABLE_ID);
        assertThat(result.previousStableVersionId()).isEqualTo(CANDIDATE_ID);
        verify(versions).markArchived(CANDIDATE_ID);
        verify(versions).restoreStable(STABLE_ID);
    }

    private static ReleaseBindingRepository.ReleaseState state(
            long revision, boolean shadowEnabled, UUID previousStableId) {
        return new ReleaseBindingRepository.ReleaseState(
                revision,
                shadowEnabled ? "SHADOW_START" : "REGISTER",
                STABLE_ID,
                CANDIDATE_ID,
                previousStableId,
                shadowEnabled,
                EVALUATION_ID,
                "{}",
                "test",
                "single-reviewer",
                now());
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.of(2026, 8, 12, 0, 0, 0, 0, ZoneOffset.UTC);
    }
}
