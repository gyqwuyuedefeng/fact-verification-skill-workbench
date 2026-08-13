package com.hsmap.factverification.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.agent.AgentVariant;
import com.hsmap.factverification.evaluation.dataset.GoldDataset;
import com.hsmap.factverification.evaluation.dataset.GoldDatasetLoader;
import com.hsmap.factverification.evaluation.dataset.GoldSample;
import com.hsmap.factverification.evaluation.gate.CandidateGate;
import com.hsmap.factverification.evaluation.gate.GateInput;
import com.hsmap.factverification.evaluation.manifest.RunManifest;
import com.hsmap.factverification.evaluation.manifest.RunManifestFactory;
import com.hsmap.factverification.evaluation.scoring.CoreMetrics;
import com.hsmap.factverification.evaluation.scoring.GoldScorer;
import com.hsmap.factverification.evaluation.scoring.MetricValue;
import com.hsmap.factverification.evaluation.scoring.SampleScore;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证同条件清单、逐样本评分和 Candidate 门禁的最小业务闭环。 */
class EvaluationCoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CanonicalJsonHasher hasher = new CanonicalJsonHasher(objectMapper);

    /** 清单锁定同一数据集和环境，且模型密钥不得进入清单或 hash 输入。 */
    @Test
    void freezesSameConditionsWithoutSecret() {
        GoldDataset dataset = new GoldDatasetLoader(objectMapper, hasher).load(Path.of("../evals/manifest.json"));
        RunManifest manifest = new RunManifestFactory(hasher)
                .create(
                        dataset,
                        "https://firelm.example/v1",
                        "qwen-company",
                        "secret-must-not-appear",
                        "agent-scope-java:2.0.1",
                        "a".repeat(64),
                        "b".repeat(64),
                        "c".repeat(64),
                        Map.of("material-1", "d".repeat(64)),
                        120);

        assertThat(manifest.sampleIds()).hasSize(30);
        assertThat(manifest.stabilityRuns()).isEqualTo(3);
        assertThat(objectMapper.valueToTree(manifest).toString())
                .doesNotContain("secret-must-not-appear")
                .contains(AgentVariant.BASELINE_INSTRUCTION);
        assertThat(manifest.modelConfigHash()).hasSize(64);
    }

    /** 准确必须同时满足主体、结论和核心证据；缺证据的 VERIFIED 判错并要求人工介入。 */
    @Test
    void scoresGoldSampleWithEvidenceInvariant() throws Exception {
        GoldSample gold = new GoldDatasetLoader(objectMapper, hasher)
                .load(Path.of("../evals/manifest.json"))
                .samples()
                .get(0);
        JsonNode invalidVerified = objectMapper.readTree(
                """
                        {"claims":[{"subject":{"companyId":"002230","companyName":"科大讯飞股份有限公司"},"status":"VERIFIED","evidence":[],"requiresHumanIntervention":false}]}
                        """);

        SampleScore score = new GoldScorer().score(gold, invalidVerified, true);

        assertThat(score.accurate()).isFalse();
        assertThat(score.completed()).isTrue();
        assertThat(score.requiresHumanIntervention()).isTrue();
    }

    /** Candidate 只有在四项不退化、无新硬错误且修复声明样本时才通过。 */
    @Test
    void requiresNonRegressionAndDeclaredFix() {
        CoreMetrics stable = metrics(20, 28, 8, 4);
        CoreMetrics improved = metrics(21, 29, 9, 3);

        assertThat(new CandidateGate()
                        .evaluate(new GateInput(
                                30,
                                stable,
                                improved,
                                List.of("known-failure-1"),
                                List.of("known-failure-1"),
                                List.of(),
                                true))
                        .status())
                .isEqualTo("PASS");
        assertThat(new CandidateGate()
                        .evaluate(new GateInput(
                                30,
                                stable,
                                metrics(19, 29, 9, 3),
                                List.of("known-failure-1"),
                                List.of(),
                                List.of("NEW_SUBJECT_MISMATCH"),
                                true))
                        .status())
                .isEqualTo("FAIL");
        assertThat(new CandidateGate()
                        .evaluate(new GateInput(30, stable, improved, List.of(), List.of(), List.of(), true))
                        .status())
                .as("没有修复任何 Stable 失败样本时不能因声明列表为空而绕过门禁")
                .isEqualTo("FAIL");
    }

    /** 只猜中主体和状态、但把材料金额归一化错了，不能计入准确率。 */
    @Test
    void rejectsIncorrectNormalizedClaimEvenWhenStatusMatches() throws Exception {
        GoldSample gold = new GoldDatasetLoader(objectMapper, hasher)
                .load(Path.of("../evals/manifest.json"))
                .samples()
                .get(1);
        JsonNode wrongValue = objectMapper.readTree(
                """
                        {"claims":[{"subject":{"companyId":"002230","companyName":"科大讯飞股份有限公司"},
                        "normalizedClaim":{"metric":"revenue","period":"2024","operator":"EQUALS","value":"1","unit":"元"},
                        "status":"VERIFIED","evidence":[{"dataset":"ads_lget_company_revenue","recordId":"r1"}],
                        "requiresHumanIntervention":false}]}
                        """);

        assertThat(new GoldScorer().score(gold, wrongValue, true).accurate()).isFalse();
    }

    private static CoreMetrics metrics(int accurate, int completed, int stableCount, int intervention) {
        return new CoreMetrics(
                MetricValue.of(accurate, 30),
                MetricValue.of(completed, 30),
                MetricValue.of(stableCount, 10),
                MetricValue.of(intervention, 30));
    }
}
