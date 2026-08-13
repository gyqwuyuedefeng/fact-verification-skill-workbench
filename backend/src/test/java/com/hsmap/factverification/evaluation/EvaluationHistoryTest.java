package com.hsmap.factverification.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.evidence.EvaluationEvidenceFreezer;
import com.hsmap.factverification.evaluation.dataset.GoldDatasetLoader;
import com.hsmap.factverification.evaluation.manifest.RunManifestFactory;
import com.hsmap.factverification.evaluation.persistence.EvaluationRunRepository;
import com.hsmap.factverification.evaluation.report.EvaluationReportGenerator;
import com.hsmap.factverification.persistence.JdbcJson;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 锁定管理评测历史、版本一对多汇总以及同批次版本比较。 */
class EvaluationHistoryTest {

    /** 汇总只能引用原始不可变评测，并单独标出注册门禁批次。 */
    @Test
    void listsHistoryAndSummarizesEveryRunContainingVersion() {
        UUID versionId = UUID.randomUUID();
        UUID registeredId = UUID.randomUUID();
        UUID latestId = UUID.randomUUID();
        EvaluationRunRepository repository = mock(EvaluationRunRepository.class);
        SkillVersionRepository skills = mock(SkillVersionRepository.class);
        when(repository.list())
                .thenReturn(List.of(
                        row(
                                latestId,
                                versionId,
                                UUID.randomUUID(),
                                OffsetDateTime.of(2026, 8, 12, 2, 0, 0, 0, ZoneOffset.UTC)),
                        row(
                                registeredId,
                                versionId,
                                UUID.randomUUID(),
                                OffsetDateTime.of(2026, 8, 12, 1, 0, 0, 0, ZoneOffset.UTC))));
        when(skills.findVersion(versionId))
                .thenReturn(java.util.Optional.of(new SkillVersionRepository.VersionRow(
                        versionId,
                        null,
                        "v2",
                        "CANDIDATE",
                        "# skill",
                        "[]",
                        "[]",
                        "{}",
                        "a".repeat(64),
                        "升级",
                        null,
                        registeredId,
                        "single-reviewer",
                        OffsetDateTime.now(ZoneOffset.UTC),
                        OffsetDateTime.now(ZoneOffset.UTC))));

        EvaluationService service = service(repository, skills);

        assertThat(service.list(null)).extracting(EvaluationRunView::id).containsExactly(latestId, registeredId);
        SkillEvaluationSummary summary = service.versionSummary(versionId);
        assertThat(summary.evaluationCount()).isEqualTo(2);
        assertThat(summary.latestEvaluationId()).isEqualTo(latestId);
        assertThat(summary.registeredEvaluationId()).isEqualTo(registeredId);
        assertThat(summary.evaluations()).extracting(EvaluationRunView::id).containsExactly(latestId, registeredId);
    }

    /** 只有同一批次中同时出现两个版本时才输出直接优劣和胜负样本。 */
    @Test
    void comparesVersionsOnlyFromSharedEvaluation() {
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        EvaluationRunRepository repository = mock(EvaluationRunRepository.class);
        when(repository.list())
                .thenReturn(
                        List.of(row(runId, left, right, OffsetDateTime.of(2026, 8, 12, 2, 0, 0, 0, ZoneOffset.UTC))));

        EvaluationComparison comparison =
                service(repository, mock(SkillVersionRepository.class)).compare(left, right);

        assertThat(comparison.comparable()).isTrue();
        assertThat(comparison.evaluationRunId()).isEqualTo(runId);
        assertThat(comparison.metricDeltas()).containsEntry("accuracy", 0.1d);
        assertThat(comparison.sampleOutcomes()).containsEntry("rightWins", 1);
    }

    private EvaluationService service(EvaluationRunRepository repository, SkillVersionRepository skills) {
        return new EvaluationService(
                repository,
                skills,
                mock(GoldDatasetLoader.class),
                mock(RunManifestFactory.class),
                mock(EvaluationRunner.class),
                mock(EvaluationEvidenceFreezer.class),
                mock(EvaluationReportGenerator.class),
                mock(WorkbenchProperties.class),
                new ObjectMapper(),
                mock(JdbcJson.class),
                mock(CanonicalJsonHasher.class));
    }

    private EvaluationRunRepository.EvaluationRow row(UUID id, UUID left, UUID right, OffsetDateTime createdAt) {
        String variants =
                """
                [{"type":"BASELINE","identifier":"BASELINE","contentHash":"%s"},
                 {"type":"SKILL","identifier":"%s","contentHash":"%s"},
                 {"type":"SKILL","identifier":"%s","contentHash":"%s"}]
                """
                        .formatted("b".repeat(64), left, "c".repeat(64), right, "d".repeat(64));
        String metric =
                """
                {"accuracy":{"definition":"准确率","numerator":8,"denominator":10,"value":%s},
                 "completionRate":{"definition":"完成率","numerator":10,"denominator":10,"value":1.0},
                 "stability":{"definition":"稳定性","numerator":9,"denominator":10,"value":0.9},
                 "humanInterventionRate":{"definition":"人工介入率","numerator":1,"denominator":10,"value":0.1}}
                """;
        String metrics = "{" + quote(left.toString()) + ":" + metric.formatted("0.8") + "," + quote(right.toString())
                + ":" + metric.formatted("0.9") + "}";
        String samples =
                """
                [{"sampleId":"s1","variantResults":{"%s":{"score":{"accurate":false}},"%s":{"score":{"accurate":true}}}}]
                """
                        .formatted(left, right);
        return new EvaluationRunRepository.EvaluationRow(
                id,
                "public-tech-2024-v1",
                "a".repeat(64),
                30,
                variants,
                "{}",
                metrics,
                samples,
                "[]",
                "COMPLETED",
                "PASS",
                "[]",
                "# report",
                "{}",
                createdAt,
                createdAt.plusMinutes(10));
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }
}
