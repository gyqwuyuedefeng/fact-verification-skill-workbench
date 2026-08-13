package com.hsmap.factverification.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.evaluation.persistence.EvaluationRunRepository;
import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.skill.SkillVersionService;
import com.hsmap.factverification.skill.VersionCardService;
import com.hsmap.factverification.skill.VersionCardView;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 被测试对象：builtin-demo.json 的冻结版本卡与 {@link VersionCardService} 反序列化路径。
 * 测试目的：确保内置导入后版本实验室能读取完整版本卡，而不是只能展示自造阶段字段。
 * 覆盖范围：三个冻结版本的版本信息、评测关联、指标、门禁和已知失败，以及 DRAFT 禁止生成卡片。
 * 前置条件：Mock 仓储仅返回 fixture 行；生产服务负责真实 DTO 反序列化与 DRAFT 语义判断。
 */
class BuiltinDemoVersionCardContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final UUID DRAFT_ID = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final Map<UUID, UUID> CARD_EVALUATIONS = Map.of(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
                    UUID.fromString("20000000-0000-0000-0000-000000000001"),
            UUID.fromString("10000000-0000-0000-0000-000000000002"),
                    UUID.fromString("20000000-0000-0000-0000-000000000002"),
            UUID.fromString("10000000-0000-0000-0000-000000000003"),
                    UUID.fromString("20000000-0000-0000-0000-000000000003"));

    /**
     * 测试场景：版本实验室依次读取初始、优化与回归三个冻结版本卡。
     * 前置条件：版本卡已经随 fixture 持久化，服务不得重新依赖当前评测或运行时状态生成。
     * 期望结果：真实 VersionCardView 的每个关键字段都可读取，并与 Skill 行和对应评测一致。
     * 断言重点：回归卡必须关联 FAIL 评测及失败样本，不能用 demoStage 代替 gateStatus/metrics。
     */
    @Test
    void deserializesCompleteFrozenCardsThroughVersionCardService() throws Exception {
        JsonNode tables = fixtureTables();
        Map<UUID, JsonNode> evaluations = rowsById(tables.path("evaluation_run"));
        Map<UUID, SkillVersionRepository.VersionRow> versions = versionRows(tables.path("skill_version"));
        SkillVersionRepository repository = mock(SkillVersionRepository.class);
        versions.forEach((id, row) -> when(repository.findVersion(id)).thenReturn(java.util.Optional.of(row)));
        VersionCardService service = new VersionCardService(
                repository, mock(EvaluationRunRepository.class), OBJECT_MAPPER);

        for (Map.Entry<UUID, UUID> expected : CARD_EVALUATIONS.entrySet()) {
            SkillVersionRepository.VersionRow row = versions.get(expected.getKey());
            JsonNode evaluation = evaluations.get(expected.getValue());
            VersionCardView card = service.get(expected.getKey());

            assertThat(card.skillKey()).isEqualTo(SkillVersionService.SKILL_KEY);
            assertThat(card.version()).isEqualTo(row.version()).isNotBlank();
            assertThat(card.status()).isEqualTo(row.status()).isNotBlank();
            String expectedParentVersion = row.parentVersionId() == null
                    ? null
                    : versions.get(row.parentVersionId()).version();
            assertThat(card.parentVersion()).isEqualTo(expectedParentVersion);
            assertThat(card.contentHash()).isEqualTo(row.contentHash()).hasSize(64);
            assertThat(card.changeSummary()).isEqualTo(row.changeSummary()).isNotBlank();
            assertThat(card.evaluationRunId()).isEqualTo(expected.getValue());
            JsonNode cardMetrics = OBJECT_MAPPER.valueToTree(card.metrics());
            assertThat(cardMetrics).isEqualTo(evaluation.path("metrics_json"));
            assertThat(card.gateStatus()).isEqualTo(evaluation.path("gate_status").asText());
            assertThat(card.knownFailures()).isNotNull();
            if (row.registeredEvaluationId() == null) {
                assertThat(card.gateStatus()).isEqualTo("FAIL");
            } else {
                assertThat(card.evaluationRunId()).isEqualTo(row.registeredEvaluationId());
            }
            if ("FAIL".equals(card.gateStatus())) {
                assertThat(card.knownFailures()).containsExactly("regression-1", "regression-3");
            }
        }
    }

    /**
     * 测试场景：版本实验室请求内置 DRAFT 的版本卡。
     * 前置条件：DRAFT 按生命周期语义没有 version、content_hash、frozen_at 或持久化版本卡。
     * 期望结果：生产服务稳定拒绝，而不是把空字段伪装成冻结卡片。
     * 断言重点：异常码沿用 VERSION_CARD_DRAFT_FORBIDDEN。
     */
    @Test
    void keepsDraftWithoutPersistedVersionCard() throws Exception {
        Map<UUID, SkillVersionRepository.VersionRow> versions =
                versionRows(fixtureTables().path("skill_version"));
        SkillVersionRepository repository = mock(SkillVersionRepository.class);
        when(repository.findVersion(DRAFT_ID)).thenReturn(java.util.Optional.of(versions.get(DRAFT_ID)));
        VersionCardService service = new VersionCardService(
                repository, mock(EvaluationRunRepository.class), OBJECT_MAPPER);

        assertThat(versions.get(DRAFT_ID).versionCardJson()).isNull();
        assertThatThrownBy(() -> service.get(DRAFT_ID))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("VERSION_CARD_DRAFT_FORBIDDEN"));
    }

    /** 从类路径读取唯一内置 fixture，确保契约测试验证的就是实际导入资源。 */
    private static JsonNode fixtureTables() throws Exception {
        try (var input = new ClassPathResource("demo-state/builtin-demo.json").getInputStream()) {
            return OBJECT_MAPPER.readTree(input).path("tables");
        }
    }

    /** 将 Skill JSON 行还原为生产仓储的 VersionRow，version_card_json 保持原始文本。 */
    private static Map<UUID, SkillVersionRepository.VersionRow> versionRows(JsonNode rows) {
        Map<UUID, SkillVersionRepository.VersionRow> result = new LinkedHashMap<>();
        rows.forEach(node -> {
            UUID id = UUID.fromString(node.path("id").asText());
            result.put(
                    id,
                    new SkillVersionRepository.VersionRow(
                            id,
                            nullableUuid(node.path("parent_version_id")),
                            nullableText(node.path("version")),
                            node.path("status").asText(),
                            node.path("skill_markdown").asText(),
                            node.path("references_json").toString(),
                            node.path("allowed_tools_json").toString(),
                            node.path("output_schema_json").toString(),
                            nullableText(node.path("content_hash")),
                            node.path("change_summary").asText(),
                            nullableJson(node.path("version_card_json")),
                            nullableUuid(node.path("registered_evaluation_id")),
                            node.path("created_by").asText(),
                            OffsetDateTime.parse(node.path("created_at").asText()),
                            nullableDateTime(node.path("frozen_at"))));
        });
        return Map.copyOf(result);
    }

    /** 按固定 UUID 索引声明式表行，方便验证版本卡与评测矩阵之间的关系。 */
    private static Map<UUID, JsonNode> rowsById(JsonNode rows) {
        Map<UUID, JsonNode> result = new LinkedHashMap<>();
        rows.forEach(node -> result.put(UUID.fromString(node.path("id").asText()), node));
        return Map.copyOf(result);
    }

    /** JSON null 保持为 Java null，避免改变 VersionCardService 的 DRAFT 分支。 */
    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 只解析 fixture 中明确存在的 UUID，null 表示没有父版本或没有注册评测。 */
    private static UUID nullableUuid(JsonNode node) {
        String value = nullableText(node);
        return value == null ? null : UUID.fromString(value);
    }

    /** 持久化 jsonb 列使用紧凑 JSON 文本交给真实 ObjectMapper 反序列化。 */
    private static String nullableJson(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    /** DRAFT 的 frozen_at 为空；冻结版本必须保留可解析的 UTC 时间。 */
    private static OffsetDateTime nullableDateTime(JsonNode node) {
        String value = nullableText(node);
        return value == null ? null : OffsetDateTime.parse(value);
    }
}
