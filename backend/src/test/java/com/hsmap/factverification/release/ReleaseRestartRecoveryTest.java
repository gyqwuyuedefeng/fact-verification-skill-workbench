package com.hsmap.factverification.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hsmap.factverification.evaluation.persistence.EvaluationRunRepository;
import com.hsmap.factverification.persistence.JdbcJson;
import com.hsmap.factverification.release.persistence.ReleaseBindingRepository;
import com.hsmap.factverification.run.persistence.VerificationRunRepository;
import com.hsmap.factverification.skill.VersionCardService;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证发布状态不依赖进程内缓存，重建服务后仍从追加事件恢复。 */
class ReleaseRestartRecoveryTest {

    /** 模拟后端重启：新 ReleaseService 读取同一持久化最新事件和完整历史。 */
    @Test
    void rebuildsCurrentBindingAndHistoryFromRepository() {
        ReleaseBindingRepository repository = mock(ReleaseBindingRepository.class);
        ReleaseBindingRepository.ReleaseState persisted = new ReleaseBindingRepository.ReleaseState(
                6,
                "ROLLBACK",
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                null,
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                false,
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                "{}",
                "演示回滚",
                "single-reviewer",
                OffsetDateTime.of(2026, 8, 12, 0, 0, 0, 0, ZoneOffset.UTC));
        when(repository.findLatest("company-material-fact-check")).thenReturn(Optional.of(persisted));
        when(repository.listHistory("company-material-fact-check")).thenReturn(List.of(persisted));

        ReleaseService restarted = service(repository);

        assertThat(restarted.current().revision()).isEqualTo(6);
        assertThat(restarted.current().stableVersionId()).isEqualTo(persisted.stableVersionId());
        assertThat(restarted.history()).extracting(ReleaseStateView::action).containsExactly("ROLLBACK");
    }

    private static ReleaseService service(ReleaseBindingRepository repository) {
        return new ReleaseService(
                repository,
                mock(SkillVersionRepository.class),
                mock(EvaluationRunRepository.class),
                mock(VerificationRunRepository.class),
                mock(InitialStableBootstrapService.class),
                mock(VersionCardService.class),
                mock(JdbcJson.class));
    }
}
