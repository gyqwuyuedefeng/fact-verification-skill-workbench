package com.hsmap.factverification.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.agent.AgentVariant;
import com.hsmap.factverification.evaluation.dataset.GoldDataset;
import com.hsmap.factverification.evaluation.dataset.GoldDatasetLoader;
import com.hsmap.factverification.evaluation.manifest.RunManifest;
import com.hsmap.factverification.evaluation.manifest.RunManifestFactory;
import com.hsmap.factverification.evaluation.scoring.GoldScorer;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

/** 用固定 fake model/MCP 证明 30 条 BASELINE + Stable + Candidate 可重复执行。 */
class EvaluationRunnerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CanonicalJsonHasher hasher = new CanonicalJsonHasher(objectMapper);

    /** 三个变体共享样本顺序和快照，前十条各独立执行三次用于稳定性。 */
    @Test
    void runsThirtySamplesForThreeVariantsAndThreeStabilityAttempts() {
        GoldDataset dataset = new GoldDatasetLoader(objectMapper, hasher).load(Path.of("../evals/manifest.json"));
        RunManifest manifest = new RunManifestFactory(hasher)
                .create(
                        dataset,
                        "https://firelm.example/v1",
                        "qwen-company",
                        "ignored-secret",
                        "agentscope-java:2.0.1",
                        "a".repeat(64),
                        "b".repeat(64),
                        "c".repeat(64),
                        Map.of("gold-material", "d".repeat(64)),
                        120);
        AtomicInteger calls = new AtomicInteger();
        EvaluationExecutionPort fake = request -> {
            calls.incrementAndGet();
            var gold = request.sample();
            var claim = objectMapper.createObjectNode();
            claim.set("subject", gold.expectedSubject());
            claim.put("status", gold.expectedStatus());
            claim.put("requiresHumanIntervention", false);
            var evidence = claim.putArray("evidence");
            if (!"INSUFFICIENT".equals(gold.expectedStatus())) {
                evidence.addObject()
                        .put("recordId", gold.manualEvidence().get(0).recordId());
            }
            return objectMapper
                    .createObjectNode()
                    .set("claims", objectMapper.createArrayNode().add(claim));
        };
        List<AgentVariant> variants = List.of(
                AgentVariant.baseline(manifest.baselineInstructionHash()),
                AgentVariant.skill("stable-v1", "e".repeat(64), Path.of("stable")),
                AgentVariant.skill("candidate-v2", "f".repeat(64), Path.of("candidate")));

        EvaluationResult result = new EvaluationRunner(fake, new GoldScorer())
                .run(UUID.randomUUID(), UUID.randomUUID(), dataset, manifest, variants);

        assertThat(result.sampleResults()).hasSize(30);
        assertThat(result.sampleResults())
                .extracting(EvaluationSampleResult::sampleId)
                .containsExactlyElementsOf(manifest.sampleIds());
        assertThat(result.metrics()).containsOnlyKeys("BASELINE", "stable-v1", "candidate-v2");
        assertThat(result.metrics().values())
                .allSatisfy(
                        metrics -> assertThat(metrics.stability().denominator()).isEqualTo(10));
        assertThat(calls).hasValue(150);
    }

    /**
     * 测试场景：真实模型单次执行耗时较长，30 条评测不能把全部尝试严格串行到不可演示。
     * 前置条件：使用可观测当前并发数的慢速 fake executor，仍按冻结清单运行 30 条和两个变体。
     * 期望结果：至少两个尝试并行，但并发峰值不超过 MVP 固定上限；最终样本顺序仍与 Run Manifest 完全一致。
     * 断言重点：加速只能改变调度时序，不能改变样本顺序、样本数量或同条件结果结构。
     */
    @Test
    void executesAttemptsWithBoundedConcurrencyAndKeepsManifestOrder() {
        GoldDataset dataset = new GoldDatasetLoader(objectMapper, hasher).load(Path.of("../evals/manifest.json"));
        RunManifest manifest = new RunManifestFactory(hasher)
                .create(
                        dataset,
                        "https://firelm.example/v1",
                        "qwen-company",
                        "ignored-secret",
                        "agentscope-java:2.0.1",
                        "a".repeat(64),
                        "b".repeat(64),
                        "c".repeat(64),
                        Map.of("gold-material", "d".repeat(64)),
                        120);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        EvaluationExecutionPort slowFake = request -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            try {
                LockSupport.parkNanos(10_000_000L);
                var claim = objectMapper.createObjectNode();
                claim.set("subject", request.sample().expectedSubject());
                claim.put("status", request.sample().expectedStatus());
                claim.put("requiresHumanIntervention", false);
                if (!"INSUFFICIENT".equals(request.sample().expectedStatus())) {
                    claim.putArray("evidence")
                            .addObject()
                            .put("recordId", request.sample().manualEvidence().get(0).recordId());
                } else {
                    claim.putArray("evidence");
                }
                return objectMapper
                        .createObjectNode()
                        .set("claims", objectMapper.createArrayNode().add(claim));
            } finally {
                active.decrementAndGet();
            }
        };
        List<AgentVariant> variants = List.of(
                AgentVariant.baseline(manifest.baselineInstructionHash()),
                AgentVariant.skill("candidate-v1", "e".repeat(64), Path.of("candidate")));

        EvaluationResult result = new EvaluationRunner(slowFake, new GoldScorer())
                .run(UUID.randomUUID(), UUID.randomUUID(), dataset, manifest, variants);

        assertThat(maximum).hasValueBetween(2, EvaluationRunner.MAX_PARALLEL_ATTEMPTS);
        assertThat(result.sampleResults())
                .extracting(EvaluationSampleResult::sampleId)
                .containsExactlyElementsOf(manifest.sampleIds());
    }
}
