package com.hsmap.factverification.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 被测试对象：{@link SkillVersionComparisonService} 对持久化升级说明身份与内容哈希的核验。
 * 测试目的：防止旧结构、错版本键或过期内容哈希冒充当前冻结版本的升级说明。
 * 覆盖范围：缺少哈希的旧 JSON，以及基础/目标 ID 或 hash 与当前查询上下文不匹配的 JSON。
 * 前置条件：版本仓储和模型客户端均为 Mock；测试不调用模型，也不连接数据库。
 */
class VersionComparisonHashContractTest {

    /**
     * 测试场景：数据库保留的是尚未记录 baseContentHash/targetContentHash 的旧结构。
     * 前置条件：旧 JSON 的版本 ID 正确，且其余摘要字段可被旧 DTO 正常解析。
     * 期望结果：读取以稳定“不兼容”错误拒绝，而不是展示成当前说明。
     * 断言重点：结构可解析不等于内容身份仍可证明。
     */
    @Test
    void rejectsLegacyPersistedComparisonWithoutContentHashes() throws Exception {
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        SkillVersionRepository repository = versions(baseId, targetId);
        VersionComparison legacy = new VersionComparison(
                targetId,
                baseId,
                null,
                null,
                "-旧规则\n+新规则",
                "COMPLETED",
                new GeneratedChangeSummary("摘要", java.util.List.of("变更"), java.util.List.of()),
                "模型生成、仅供审核参考",
                null,
                "model",
                OffsetDateTime.of(2026, 8, 14, 0, 0, 0, 0, ZoneOffset.UTC),
                true);
        com.fasterxml.jackson.databind.node.ObjectNode oldStructure =
                (com.fasterxml.jackson.databind.node.ObjectNode) mapper().valueToTree(legacy);
        oldStructure.remove("baseContentHash");
        oldStructure.remove("targetContentHash");
        String json = mapper().writeValueAsString(oldStructure);
        when(repository.findComparisonSummary(targetId, baseId)).thenReturn(Optional.of(json));

        assertThatThrownBy(() -> service(repository).get(targetId, baseId))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("SKILL_VERSION_COMPARISON_INCOMPATIBLE"));
    }

    /**
     * 测试场景：基础版本键下保存的 JSON 自称属于另一版本对，且哈希也不是当前冻结内容。
     * 前置条件：JSON 含新结构的两个 hash 字段，排除“仅因缺字段失败”的干扰。
     * 期望结果：读取以身份不匹配错误拒绝，页面不能恢复错误摘要。
     * 断言重点：读取必须同时核对查询 ID 与两份当前 contentHash。
     */
    @Test
    void rejectsPersistedComparisonForDifferentIdsAndHashes() {
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        SkillVersionRepository repository = versions(baseId, targetId);
        String json = """
                {
                  "targetVersionId":"%s",
                  "baseVersionId":"%s",
                  "baseContentHash":"%s",
                  "targetContentHash":"%s",
                  "deterministicDiff":"-旧规则\\n+新规则",
                  "summaryStatus":"COMPLETED",
                  "generatedSummary":{"headline":"错误摘要","changes":[],"reviewRisks":[]},
                  "advisory":"模型生成、仅供审核参考",
                  "errorCode":null,
                  "modelId":"model",
                  "generatedAt":"2026-08-14T00:00:00Z",
                  "persisted":true
                }
                """
                .formatted(UUID.randomUUID(), UUID.randomUUID(), "c".repeat(64), "d".repeat(64));
        when(repository.findComparisonSummary(targetId, baseId)).thenReturn(Optional.of(json));

        assertThatThrownBy(() -> service(repository).get(targetId, baseId))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("SKILL_VERSION_COMPARISON_MISMATCH"));
    }

    /** 创建只读取持久化说明的服务，模型客户端不应在 GET 路径被调用。 */
    private static SkillVersionComparisonService service(SkillVersionRepository repository) {
        return new SkillVersionComparisonService(repository, mock(SkillChangeSummaryClient.class), mapper());
    }

    /** 创建当前基础/目标冻结版本；两份不同 hash 是读取核对的事实来源。 */
    private static SkillVersionRepository versions(UUID baseId, UUID targetId) {
        SkillVersionRepository repository = mock(SkillVersionRepository.class);
        when(repository.findVersion(baseId)).thenReturn(Optional.of(row(baseId, "a".repeat(64), "旧规则")));
        when(repository.findVersion(targetId)).thenReturn(Optional.of(row(targetId, "b".repeat(64), "新规则")));
        return repository;
    }

    /** 生成合法冻结版本投影，使失败只来自保存说明的身份或 hash。 */
    private static SkillVersionRepository.VersionRow row(UUID id, String hash, String markdown) {
        OffsetDateTime now = OffsetDateTime.of(2026, 8, 14, 0, 0, 0, 0, ZoneOffset.UTC);
        return new SkillVersionRepository.VersionRow(
                id,
                null,
                "v-" + hash.substring(0, 12),
                "CANDIDATE",
                markdown,
                "[]",
                "[]",
                "{}",
                hash,
                "说明",
                null,
                null,
                "single-reviewer",
                now,
                now);
    }

    /** 统一启用 Java time 模块，保持与应用 ObjectMapper 相同的时间格式。 */
    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
