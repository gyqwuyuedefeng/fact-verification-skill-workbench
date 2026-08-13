package com.hsmap.factverification.release;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hsmap.factverification.evaluation.persistence.EvaluationRunRepository;
import com.hsmap.factverification.persistence.JdbcJson;
import com.hsmap.factverification.release.persistence.ReleaseBindingRepository;
import com.hsmap.factverification.run.persistence.VerificationRunRepository;
import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.skill.VersionCardService;
import com.hsmap.factverification.skill.VersionCardView;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 被测试对象：{@link ReleaseService} 的 Candidate 注册评测绑定门禁。
 * 测试目的：已有 Stable 时，禁止使用只包含通用基线和 Candidate 的首版评测绕过 Stable 对照。
 * 覆盖范围：注册前的当前发布状态读取、评测变体校验以及失败时不写版本卡和发布事件。
 * 前置条件：Candidate 已冻结、评测门禁为 PASS，但评测变体故意缺少当前 Stable。
 */
class ReleaseEvaluationBindingTest {

    private static final UUID STABLE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CANDIDATE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID EVALUATION_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");

    /**
     * 测试场景：已有 Stable 后注册下一 Candidate，但所选评测只运行了 BASELINE 与 Candidate。
     * 前置条件：Candidate 冻结且评测自身为 PASS，当前发布绑定明确指向另一个 Stable。
     * 期望结果：注册以稳定业务错误码失败，不能关联版本卡或追加 REGISTER revision。
     * 断言重点：后续版本必须在相同评测中包含当前 Stable，首版双变体特例不能被复用。
     */
    @Test
    void rejectsRegistrationWhenEvaluationOmitsCurrentStable() {
        ReleaseBindingRepository releases = Mockito.mock(ReleaseBindingRepository.class);
        SkillVersionRepository versions = Mockito.mock(SkillVersionRepository.class);
        EvaluationRunRepository evaluations = Mockito.mock(EvaluationRunRepository.class);
        InitialStableBootstrapService initialStable = Mockito.mock(InitialStableBootstrapService.class);
        VersionCardService cards = Mockito.mock(VersionCardService.class);
        JdbcJson jdbcJson = Mockito.mock(JdbcJson.class);
        ReleaseService service = new ReleaseService(
                releases,
                versions,
                evaluations,
                Mockito.mock(VerificationRunRepository.class),
                initialStable,
                cards,
                jdbcJson);

        when(versions.findVersion(CANDIDATE_ID)).thenReturn(Optional.of(candidate()));
        when(evaluations.findBootstrap(EVALUATION_ID))
                .thenReturn(Optional.of(
                        new BootstrapEvaluation(EVALUATION_ID, "PASS", Set.of("BASELINE", CANDIDATE_ID.toString()))));
        when(releases.findLatestForUpdate(InitialStableBootstrapService.SKILL_KEY))
                .thenReturn(Optional.of(currentStable()));
        when(cards.build(candidate(), EVALUATION_ID)).thenReturn(Mockito.mock(VersionCardView.class));
        when(jdbcJson.write(Mockito.any())).thenReturn("{}");
        when(versions.registerCandidate(CANDIDATE_ID, EVALUATION_ID, "{}")).thenReturn(1);

        assertThatThrownBy(() -> service.register(CANDIDATE_ID, EVALUATION_ID, "准备注册后续版本"))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo("EVALUATION_STABLE_MISMATCH");

        verify(versions, never()).registerCandidate(CANDIDATE_ID, EVALUATION_ID, "{}");
        verify(releases, never()).append(Mockito.any());
        verify(initialStable, never()).initialize(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    private static SkillVersionRepository.VersionRow candidate() {
        OffsetDateTime now = now();
        return new SkillVersionRepository.VersionRow(
                CANDIDATE_ID,
                STABLE_ID,
                "0.2.0",
                "CANDIDATE",
                "# 企业材料事实核验",
                "[]",
                "[]",
                "{}",
                "a".repeat(64),
                "修复一个已知失败样本",
                null,
                null,
                "single-reviewer",
                now,
                now);
    }

    private static ReleaseBindingRepository.ReleaseState currentStable() {
        return new ReleaseBindingRepository.ReleaseState(
                1,
                "INITIALIZE",
                STABLE_ID,
                null,
                null,
                false,
                EVALUATION_ID,
                "{}",
                "初始 Stable",
                "single-reviewer",
                now());
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.of(2026, 8, 12, 0, 0, 0, 0, ZoneOffset.UTC);
    }
}
