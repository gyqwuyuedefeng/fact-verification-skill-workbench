package com.hsmap.factverification.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hsmap.factverification.run.persistence.VerificationRunRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 影子真实任务没有金标，只允许汇总运行、人工复核和结论差异。 */
class ShadowHistoryServiceTest {

    /** 历史投影保留企业/文件/版本和复核状态，但 accuracyAvailable 永远为 false。 */
    @Test
    void summarizesShadowObservationWithoutAccuracy() {
        VerificationRunRepository repository = mock(VerificationRunRepository.class);
        when(repository.listShadowRuns())
                .thenReturn(List.of(row("PASS", "COMPLETED", 4, 1), row("FAIL", "FAILED", 0, 0)));

        ShadowHistory result = new ShadowHistoryService(repository).list(null, null);

        assertThat(result.accuracyAvailable()).isFalse();
        assertThat(result.items()).hasSize(2);
        assertThat(result.summary())
                .containsEntry("total", 2)
                .containsEntry("completed", 1)
                .containsEntry("pass", 1)
                .containsEntry("fail", 1)
                .containsEntry("differentClaims", 1);
    }

    private VerificationRunRepository.ShadowRunRow row(
            String reviewStatus, String shadowStatus, int agreementCount, int differenceCount) {
        return new VerificationRunRepository.ShadowRunRow(
                UUID.randomUUID(),
                "模拟企业经营简报.md",
                "模拟科技股份有限公司",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "COMPLETED",
                shadowStatus,
                reviewStatus,
                agreementCount,
                differenceCount,
                OffsetDateTime.of(2026, 8, 12, 1, 0, 0, 0, ZoneOffset.UTC));
    }
}
