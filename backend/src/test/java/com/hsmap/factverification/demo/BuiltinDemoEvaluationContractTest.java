package com.hsmap.factverification.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.evidence.EvaluationEvidenceFreezer;
import com.hsmap.factverification.evaluation.EvaluationComparison;
import com.hsmap.factverification.evaluation.EvaluationRunner;
import com.hsmap.factverification.evaluation.EvaluationService;
import com.hsmap.factverification.evaluation.dataset.GoldDatasetLoader;
import com.hsmap.factverification.evaluation.manifest.RunManifestFactory;
import com.hsmap.factverification.evaluation.persistence.EvaluationRunRepository;
import com.hsmap.factverification.evaluation.report.EvaluationReportGenerator;
import com.hsmap.factverification.persistence.JdbcJson;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 被测试对象：builtin-demo.json 中三次评测与 {@link EvaluationService} 的现有读取契约。
 * 测试目的：防止演示 fixture 只提供页面无法比较的占位指标或空样本。
 * 覆盖范围：四指标机器可复核形状、样本数量、逐变体评分，以及优化/回归版本的真实胜负比较。
 * 前置条件：仅用 Mock 仓储返回 fixture 行，不连接数据库，也不调用模型或 MCP。
 */
class BuiltinDemoEvaluationContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final UUID STABLE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID IMPROVED_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID REGRESSION_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final List<String> METRIC_NAMES =
            List.of("accuracy", "completionRate", "stability", "humanInterventionRate");
    private static final Set<String> ALLOWED_GOLD_STATUSES = Set.of("VERIFIED", "CONFLICT", "INSUFFICIENT");
    private static final Map<String, String> METRIC_DEFINITIONS = Map.of(
            "accuracy", "主体、核验结论和核心证据均正确的金标主张数 / 金标主张总数",
            "completionRate", "时限内完成并产生合法结果的样本数 / 样本总数",
            "stability", "同条件三次运行主体和结论一致的抽样数 / 稳定性抽样总数",
            "humanInterventionRate", "主动请求确认或发布前必须修正的样本数 / 样本总数");

    /**
     * 测试场景：管理页读取三次内置评测的指标与样本下钻。
     * 前置条件：每次评测的变体数不同，但 sample_count 都是三条。
     * 期望结果：每个变体都有四项指标定义和可复核计数，每条样本也覆盖全部参评变体。
     * 断言重点：指标 value 必须等于 numerator/denominator，不能保留与样本数量矛盾的演示小数。
     */
    @Test
    void exposesCompleteMetricsAndSampleScoresThroughEvaluationService() throws Exception {
        JsonNode evaluations = fixtureEvaluations();
        EvaluationService service = service(evaluations);
        List<String> goldStatuses = new ArrayList<>();

        for (JsonNode evaluation : evaluations) {
            List<String> variants = variantIdentifiers(evaluation);
            JsonNode metrics = evaluation.path("metrics_json");
            assertThat(fieldNames(metrics)).containsExactlyElementsOf(variants);
            for (String variant : variants) {
                JsonNode matrix = metrics.path(variant);
                assertThat(fieldNames(matrix)).containsExactlyElementsOf(METRIC_NAMES);
                for (String metricName : METRIC_NAMES) {
                    JsonNode metric = matrix.path(metricName);
                    assertThat(metric.path("definition").asText()).isEqualTo(METRIC_DEFINITIONS.get(metricName));
                    assertThat(metric.path("numerator").isIntegralNumber()).isTrue();
                    assertThat(metric.path("denominator").asInt()).isPositive();
                    assertThat(metric.path("value").asDouble())
                            .isCloseTo(
                                    (double) metric.path("numerator").asLong()
                                            / metric.path("denominator").asLong(),
                                    org.assertj.core.data.Offset.offset(0.000_000_1d));
                }
            }

            UUID evaluationId = UUID.fromString(evaluation.path("id").asText());
            List<Map<String, Object>> samples = service.samples(evaluationId);
            assertThat(samples).hasSize(evaluation.path("sample_count").asInt());
            for (Map<String, Object> sample : samples) {
                JsonNode sampleNode = OBJECT_MAPPER.valueToTree(sample);
                goldStatuses.add(sampleNode.path("gold").path("expectedStatus").asText());
                assertThat(fieldNames(sampleNode.path("variantResults"))).containsExactlyElementsOf(variants);
                for (String variant : variants) {
                    assertThat(sampleNode.path("variantResults").path(variant).path("score").path("accurate").isBoolean())
                            .isTrue();
                }
            }
        }
        assertThat(goldStatuses).containsOnlyElementsOf(ALLOWED_GOLD_STATUSES);

        JsonNode initialSamples = evaluations.get(0).path("sample_results_json");
        long stableWins = accurateWins(initialSamples, "BASELINE", STABLE_ID.toString());
        long initialTies = accurateTies(initialSamples, "BASELINE", STABLE_ID.toString());
        assertThat(stableWins).isEqualTo(1);
        assertThat(initialTies).isEqualTo(2);
    }

    /**
     * 测试场景：管理页比较 Stable 与优化版、Stable 与回归版。
     * 前置条件：比较必须复用同一评测批次中的逐样本评分，不能用独立的演示文案代替。
     * 期望结果：优化版准确率提高且有右侧胜出样本；回归版准确率下降且有左侧胜出样本。
     * 断言重点：三条样本必须全部进入 wins/ties，避免空数组产生全零的虚假比较。
     */
    @Test
    void comparesImprovementAndRegressionUsingFixtureSampleOutcomes() throws Exception {
        EvaluationService service = service(fixtureEvaluations());

        EvaluationComparison improvement = service.compare(STABLE_ID, IMPROVED_ID);
        assertThat(improvement.comparable()).isTrue();
        assertThat(improvement.metricDeltas()).containsEntry("accuracy", 0.3333d);
        assertThat(improvement.sampleOutcomes().get("rightWins")).isPositive();
        assertThat(improvement.sampleOutcomes().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(3);

        EvaluationComparison regression = service.compare(STABLE_ID, REGRESSION_ID);
        assertThat(regression.comparable()).isTrue();
        assertThat(regression.metricDeltas().get("accuracy")).isNegative();
        assertThat(regression.sampleOutcomes().get("leftWins")).isPositive();
        assertThat(regression.sampleOutcomes().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(3);
    }

    /** 使用真实 EvaluationService 消费 fixture JSON，只替换其数据库查询边界。 */
    private static EvaluationService service(JsonNode evaluationsNode) {
        List<EvaluationRunRepository.EvaluationRow> rows = new ArrayList<>();
        evaluationsNode.forEach(node -> rows.add(evaluationRow(node)));
        EvaluationRunRepository evaluations = mock(EvaluationRunRepository.class);
        when(evaluations.list()).thenReturn(List.copyOf(rows));
        rows.forEach(row -> when(evaluations.find(row.id())).thenReturn(java.util.Optional.of(row)));
        return new EvaluationService(
                evaluations,
                mock(SkillVersionRepository.class),
                mock(GoldDatasetLoader.class),
                mock(RunManifestFactory.class),
                mock(EvaluationRunner.class),
                mock(EvaluationEvidenceFreezer.class),
                mock(EvaluationReportGenerator.class),
                mock(WorkbenchProperties.class),
                OBJECT_MAPPER,
                mock(JdbcJson.class),
                mock(CanonicalJsonHasher.class));
    }

    /** 将声明式表行映射为生产仓储的完整只读投影，避免测试另造一套评测 DTO。 */
    private static EvaluationRunRepository.EvaluationRow evaluationRow(JsonNode node) {
        return new EvaluationRunRepository.EvaluationRow(
                UUID.fromString(node.path("id").asText()),
                node.path("dataset_version").asText(),
                node.path("dataset_hash").asText(),
                node.path("sample_count").asInt(),
                json(node.path("variants_json")),
                json(node.path("run_manifest_json")),
                json(node.path("metrics_json")),
                json(node.path("sample_results_json")),
                json(node.path("failures_json")),
                node.path("status").asText(),
                node.path("gate_status").asText(),
                json(node.path("gate_reasons_json")),
                node.path("report_markdown").asText(),
                json(node.path("report_json")),
                OffsetDateTime.parse(node.path("created_at").asText()),
                OffsetDateTime.parse(node.path("finished_at").asText()));
    }

    /** 从类路径读取唯一内置 fixture，契约测试不复制状态声明。 */
    private static JsonNode fixtureEvaluations() throws Exception {
        try (var input = new ClassPathResource("demo-state/builtin-demo.json").getInputStream()) {
            return OBJECT_MAPPER.readTree(input).path("tables").path("evaluation_run");
        }
    }

    /** 按 JSON 声明顺序读取参评标识，顺序同时对应页面的固定展示顺序。 */
    private static List<String> variantIdentifiers(JsonNode evaluation) {
        List<String> identifiers = new ArrayList<>();
        evaluation.path("variants_json").forEach(node -> identifiers.add(node.path("identifier").asText()));
        return List.copyOf(identifiers);
    }

    /** 保留 ObjectNode 的声明顺序，防止指标或变体顺序在 fixture 中漂移。 */
    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }

    /** 统计右侧版本独有的准确样本，明确锁定初始阶段相对 BASELINE 的可演示改进。 */
    private static long accurateWins(JsonNode samples, String left, String right) {
        long wins = 0;
        for (JsonNode sample : samples) {
            boolean leftAccurate = sample.path("variantResults").path(left).path("score").path("accurate").asBoolean();
            boolean rightAccurate = sample.path("variantResults").path(right).path("score").path("accurate").asBoolean();
            if (!leftAccurate && rightAccurate) {
                wins++;
            }
        }
        return wins;
    }

    /** 统计两侧准确性结论一致的样本，避免演示比较退化为 wins/ties 全零。 */
    private static long accurateTies(JsonNode samples, String left, String right) {
        long ties = 0;
        for (JsonNode sample : samples) {
            boolean leftAccurate = sample.path("variantResults").path(left).path("score").path("accurate").asBoolean();
            boolean rightAccurate = sample.path("variantResults").path(right).path("score").path("accurate").asBoolean();
            if (leftAccurate == rightAccurate) {
                ties++;
            }
        }
        return ties;
    }

    /** fixture 内嵌 JSON 列按 PostgreSQL jsonb 文本的生产读取形态交给服务。 */
    private static String json(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }
}
