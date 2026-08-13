package com.hsmap.factverification.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 被测试对象：{@link SkillVersionComparisonService} 的可恢复版本升级说明能力。
 * 测试目的：验证模型生成结果会完整持久化，并在读取或模型失败时保持审核页面可用。
 * 覆盖范围：首次生成保存、已保存说明恢复，以及重新生成失败时保留旧成功结果。
 * 前置条件：冻结版本由仓储 Mock 返回；模型客户端仅模拟外部模型调用，不连接网络。
 */
class SkillVersionComparisonServiceTest {

    /**
     * 测试场景：管理员首次为两个冻结版本生成升级说明。
     * 前置条件：仓储存在基准和目标版本，模型返回包含“强化单位归一化”的有效摘要。
     * 期望结果：服务返回已持久化的完成结果，并将完整说明 JSON 写入目标版本的基础版本键下。
     * 断言重点：持久化写入使用目标版本 ID、基础版本 ID 和模型摘要，避免刷新页面后丢失结果。
     */
    @Test
    void generatesAndPersistsDeterministicDiffAndAdvisorySummary() {
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        SkillVersionRepository versions = versions(baseId, targetId);
        when(versions.saveComparisonSummary(eq(targetId), eq(baseId), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(1);
        SkillChangeSummaryClient client = (base, target) ->
                new GeneratedChangeSummary("强化单位归一化", java.util.List.of("万元统一转元"), java.util.List.of("注意历史报表单位"));

        VersionComparison result = service(versions, client).generate(targetId, baseId);

        assertThat(result.deterministicDiff()).contains("-旧规则", "+新规则：金额统一转元");
        assertThat(result.summaryStatus()).isEqualTo("COMPLETED");
        assertThat(result.generatedSummary().headline()).contains("单位归一化");
        assertThat(result.advisory()).isEqualTo("模型生成、仅供审核参考");
        assertThat(result.persisted()).isTrue();
        verify(versions).saveComparisonSummary(eq(targetId), eq(baseId), contains("强化单位归一化"));
    }

    /**
     * 测试场景：管理员首次打开尚未生成说明的版本比较页面。
     * 前置条件：两个版本均已冻结，但目标版本的 JSONB 字段没有该基础版本键。
     * 期望结果：服务返回 NOT_GENERATED，且不调用模型客户端。
     * 断言重点：读取端不得把“尚未生成”误转为模型调用或模型故障状态。
     */
    @Test
    void returnsNotGeneratedWhenNoPersistedSummaryExists() {
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        SkillVersionRepository repository = versions(baseId, targetId);
        SkillChangeSummaryClient summaryClient = mock(SkillChangeSummaryClient.class);
        when(repository.findComparisonSummary(targetId, baseId)).thenReturn(Optional.empty());

        VersionComparison result = service(repository, summaryClient).get(targetId, baseId);

        assertThat(result.summaryStatus()).isEqualTo("NOT_GENERATED");
        assertThat(result.generatedSummary()).isNull();
        assertThat(result.persisted()).isFalse();
        verifyNoMoreInteractions(summaryClient);
    }

    /**
     * 测试场景：管理员刷新已生成升级说明的版本比较页面。
     * 前置条件：仓储已保存一份完整的完成结果 JSON，页面只请求读取接口。
     * 期望结果：服务恢复 COMPLETED 状态和生成时间，且不调用模型客户端。
     * 断言重点：GET 仅恢复持久化快照，避免刷新页面重复消耗模型调用。
     */
    @Test
    void restoresPersistedSummaryWithoutCallingModel() throws Exception {
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        SkillVersionRepository repository = versions(baseId, targetId);
        SkillChangeSummaryClient summaryClient = mock(SkillChangeSummaryClient.class);
        VersionComparison previous = completed(targetId, baseId);
        when(repository.findComparisonSummary(targetId, baseId))
                .thenReturn(Optional.of(new ObjectMapper().findAndRegisterModules().writeValueAsString(previous)));

        VersionComparison restored = service(repository, summaryClient).get(targetId, baseId);

        assertThat(restored.summaryStatus()).isEqualTo("COMPLETED");
        assertThat(restored.generatedAt()).isNotNull();
        assertThat(restored.generatedSummary()).isEqualTo(previous.generatedSummary());
        verifyNoMoreInteractions(summaryClient);
    }

    /**
     * 测试场景：管理员重新生成已有升级说明，但模型暂时不可用。
     * 前置条件：仓储保有先前成功结果，当前模型客户端抛出运行时调用异常。
     * 期望结果：服务保留先前摘要，并以 MODEL_SUMMARY_UNAVAILABLE 标记本次刷新失败。
     * 断言重点：模型故障不能覆盖已审核过的成功说明，也不能把原始模型异常暴露给调用方。
     */
    @Test
    void keepsPreviousSummaryWhenRegenerationIsUnavailable() throws Exception {
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        SkillVersionRepository repository = versions(baseId, targetId);
        SkillChangeSummaryClient client = (base, target) -> {
            throw new IllegalStateException("model unavailable");
        };
        VersionComparison previous = completed(targetId, baseId);
        when(repository.findComparisonSummary(targetId, baseId))
                .thenReturn(Optional.of(new ObjectMapper().findAndRegisterModules().writeValueAsString(previous)));

        VersionComparison failedRegeneration = service(repository, client).generate(targetId, baseId);

        assertThat(failedRegeneration.generatedSummary()).isEqualTo(previous.generatedSummary());
        assertThat(failedRegeneration.errorCode()).isEqualTo("MODEL_SUMMARY_UNAVAILABLE");
    }

    private SkillVersionComparisonService service(SkillVersionRepository repository, SkillChangeSummaryClient client) {
        return new SkillVersionComparisonService(repository, client, new ObjectMapper().findAndRegisterModules());
    }

    private static VersionComparison completed(UUID targetId, UUID baseId) {
        return new VersionComparison(
                targetId,
                baseId,
                "-旧规则\n+新规则：金额统一转元",
                "COMPLETED",
                new GeneratedChangeSummary("强化单位归一化", java.util.List.of("万元统一转元"), java.util.List.of("注意历史报表单位")),
                "模型生成、仅供审核参考",
                null,
                "company-qwen",
                OffsetDateTime.of(2026, 8, 12, 0, 0, 0, 0, ZoneOffset.UTC),
                true);
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
