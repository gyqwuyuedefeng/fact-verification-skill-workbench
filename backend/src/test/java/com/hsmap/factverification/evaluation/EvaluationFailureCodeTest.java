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
import com.hsmap.factverification.shared.ServiceException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 被测试对象：EvaluationRunner 的单次变体失败记录。
 * 测试目的：确保真实模型评测报告保留脱敏业务错误码，使 schema、超时和缺结果问题可以区分。
 * 覆盖范围：EvaluationExecutionPort 抛出 ServiceException 时生成的 EvaluationAttemptResult。
 * 关键前置条件：错误描述可能包含内部细节，因此报告只允许保留稳定 code，不保存异常消息或堆栈。
 */
class EvaluationFailureCodeTest {

    /**
     * 测试场景：每个评测尝试都因模型结果不是合法 JSON 而失败。
     * 前置条件：执行端抛出带 AGENT_RESULT_INVALID code 的 ServiceException。
     * 期望结果：所有尝试继续失败关闭，但报告可明确区分该错误与超时或未知运行时异常。
     * 断言重点：errorCode 必须等于业务异常 code，不能被统一覆盖成无诊断价值的笼统字符串。
     */
    @Test
    void preservesSanitizedServiceExceptionCodeForFailedAttempts() {
        ObjectMapper objectMapper = new ObjectMapper();
        CanonicalJsonHasher hasher = new CanonicalJsonHasher(objectMapper);
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
        EvaluationExecutionPort failingExecutor = request -> {
            throw new ServiceException("AGENT_RESULT_INVALID", "原始错误不得进入评测报告");
        };

        EvaluationResult result = new EvaluationRunner(failingExecutor, new GoldScorer())
                .run(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        dataset,
                        manifest,
                        List.of(
                                AgentVariant.baseline(manifest.baselineInstructionHash()),
                                AgentVariant.skill("candidate-v1", "e".repeat(64), Path.of("candidate"))));

        assertThat(result.sampleResults())
                .flatExtracting(sample -> sample.variantResults().values())
                .flatExtracting(EvaluationVariantResult::attempts)
                .extracting(EvaluationAttemptResult::errorCode)
                .containsOnly("AGENT_RESULT_INVALID");
    }
}
