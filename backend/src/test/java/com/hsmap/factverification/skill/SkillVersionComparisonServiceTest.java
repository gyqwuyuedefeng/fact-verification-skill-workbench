package com.hsmap.factverification.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 版本升级说明始终以确定性原文差异为底座，模型摘要只能作为可失败的审核辅助。 */
class SkillVersionComparisonServiceTest {

    /** 模型可用时同时返回逐行差异和明确的仅供参考摘要。 */
    @Test
    void returnsDeterministicDiffAndAdvisorySummary() {
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        SkillVersionRepository versions = versions(baseId, targetId);
        SkillChangeSummaryClient client = (base, target) ->
                new GeneratedChangeSummary("强化单位归一化", java.util.List.of("万元统一转元"), java.util.List.of("注意历史报表单位"));

        VersionComparison result = new SkillVersionComparisonService(versions, client).compare(targetId, baseId);

        assertThat(result.deterministicDiff()).contains("-旧规则", "+新规则：金额统一转元");
        assertThat(result.summaryStatus()).isEqualTo("COMPLETED");
        assertThat(result.generatedSummary().headline()).contains("单位归一化");
        assertThat(result.advisory()).isEqualTo("模型生成、仅供审核参考");
    }

    /** 模型失败不得阻断人工查看原始 Skill 差异。 */
    @Test
    void keepsDiffWhenModelSummaryIsUnavailable() {
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        SkillChangeSummaryClient client = (base, target) -> {
            throw new IllegalStateException("model unavailable");
        };

        VersionComparison result =
                new SkillVersionComparisonService(versions(baseId, targetId), client).compare(targetId, baseId);

        assertThat(result.deterministicDiff()).contains("+新规则：金额统一转元");
        assertThat(result.summaryStatus()).isEqualTo("UNAVAILABLE");
        assertThat(result.generatedSummary()).isNull();
        assertThat(result.errorCode()).isEqualTo("MODEL_SUMMARY_UNAVAILABLE");
    }

    private SkillVersionRepository versions(UUID baseId, UUID targetId) {
        SkillVersionRepository repository = mock(SkillVersionRepository.class);
        when(repository.findVersion(baseId)).thenReturn(Optional.of(row(baseId, "# Skill\n旧规则")));
        when(repository.findVersion(targetId)).thenReturn(Optional.of(row(targetId, "# Skill\n新规则：金额统一转元")));
        return repository;
    }

    private SkillVersionRepository.VersionRow row(UUID id, String markdown) {
        OffsetDateTime now = OffsetDateTime.of(2026, 8, 12, 0, 0, 0, 0, ZoneOffset.UTC);
        return new SkillVersionRepository.VersionRow(
                id,
                null,
                "v1",
                "CANDIDATE",
                markdown,
                "[]",
                "[]",
                "{}",
                "a".repeat(64),
                "change",
                null,
                null,
                "single-reviewer",
                now,
                now);
    }
}
