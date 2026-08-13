package com.hsmap.factverification.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.hsmap.factverification.agent.AgentVariant;
import com.hsmap.factverification.evaluation.dataset.GoldDataset;
import com.hsmap.factverification.evaluation.dataset.GoldSample;
import com.hsmap.factverification.evaluation.manifest.RunManifest;
import com.hsmap.factverification.evaluation.scoring.CoreMetrics;
import com.hsmap.factverification.evaluation.scoring.GoldScorer;
import com.hsmap.factverification.evaluation.scoring.SampleScore;
import com.hsmap.factverification.evaluation.scoring.StabilityObservation;
import com.hsmap.factverification.shared.ServiceException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 以固定顺序运行 BASELINE 和冻结 Skill 变体。
 *
 * <p>MVP 对清单前 10 条做三次稳定性执行，既满足三次原始结果留存，也控制公司模型调用成本。
 */
public final class EvaluationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(EvaluationRunner.class);
    public static final int STABILITY_SAMPLE_COUNT = 10;
    public static final int MAX_PARALLEL_ATTEMPTS = 6;

    private final EvaluationExecutionPort executor;
    private final GoldScorer scorer;

    public EvaluationRunner(EvaluationExecutionPort executor, GoldScorer scorer) {
        this.executor = executor;
        this.scorer = scorer;
    }

    /** 运行一次不可变清单；单样本失败被记录，不中断其他变体和样本。 */
    public EvaluationResult run(
            UUID evaluationId,
            UUID evidenceSnapshotId,
            GoldDataset dataset,
            RunManifest manifest,
            List<AgentVariant> variants) {
        validateInputs(dataset, manifest, variants);
        AtomicInteger workerSequence = new AtomicInteger();
        // 公司模型的一次 ReAct 工具链通常需要数十秒。若 100～150 个尝试完全串行，真实比赛评测会长达数小时。
        // MVP 只在单次评测内部使用六个守护线程并发，不增加队列服务或跨进程调度；任务按清单顺序提交，结果仍按清单顺序收集。
        ExecutorService attempts = Executors.newFixedThreadPool(MAX_PARALLEL_ATTEMPTS, runnable -> {
            Thread worker = new Thread(runnable, "evaluation-attempt-" + workerSequence.incrementAndGet());
            worker.setDaemon(true);
            return worker;
        });
        try {
            return runPlanned(evaluationId, evidenceSnapshotId, dataset, manifest, variants, attempts);
        } finally {
            // 正常路径在返回前已经 join 全部任务；异常路径立即中断尚未开始的尝试，避免失败评测继续消耗模型资源。
            attempts.shutdownNow();
        }
    }

    /** 先按冻结清单提交全部尝试，再按相同顺序收集，兼顾耗时和可复现的报告排序。 */
    private EvaluationResult runPlanned(
            UUID evaluationId,
            UUID evidenceSnapshotId,
            GoldDataset dataset,
            RunManifest manifest,
            List<AgentVariant> variants,
            ExecutorService attempts) {
        List<EvaluationSampleResult> sampleResults = new ArrayList<>();
        Map<String, List<SampleScore>> scoresByVariant = new LinkedHashMap<>();
        Map<String, List<StabilityObservation>> stabilityByVariant = new LinkedHashMap<>();
        variants.forEach(variant -> {
            scoresByVariant.put(variant.identifier(), new ArrayList<>());
            stabilityByVariant.put(variant.identifier(), new ArrayList<>());
        });

        List<PlannedSample> plannedSamples = new ArrayList<>();
        for (int sampleIndex = 0; sampleIndex < dataset.samples().size(); sampleIndex++) {
            GoldSample sample = dataset.samples().get(sampleIndex);
            Map<String, List<CompletableFuture<EvaluationAttemptResult>>> attemptsByVariant = new LinkedHashMap<>();
            for (AgentVariant variant : variants) {
                int attemptCount = sampleIndex < STABILITY_SAMPLE_COUNT ? manifest.stabilityRuns() : 1;
                List<CompletableFuture<EvaluationAttemptResult>> variantAttempts = new ArrayList<>();
                for (int attempt = 1; attempt <= attemptCount; attempt++) {
                    int attemptNumber = attempt;
                    variantAttempts.add(CompletableFuture.supplyAsync(
                            () -> execute(
                                    evaluationId,
                                    evidenceSnapshotId,
                                    sample,
                                    variant,
                                    manifest,
                                    attemptNumber),
                            attempts));
                }
                attemptsByVariant.put(variant.identifier(), List.copyOf(variantAttempts));
            }
            plannedSamples.add(new PlannedSample(sample, Map.copyOf(attemptsByVariant)));
        }

        for (PlannedSample planned : plannedSamples) {
            GoldSample sample = planned.sample();
            Map<String, EvaluationVariantResult> variantResults = new LinkedHashMap<>();
            for (AgentVariant variant : variants) {
                List<EvaluationAttemptResult> rawAttempts = planned.attemptsByVariant().get(variant.identifier()).stream()
                        .map(CompletableFuture::join)
                        .toList();
                EvaluationAttemptResult primary = rawAttempts.get(0);
                boolean completed = primary.errorCode() == null
                        && primary.durationMs()
                                <= Duration.ofSeconds(manifest.timeoutSeconds()).toMillis();
                SampleScore score = scorer.score(sample, primary.output(), completed);
                scoresByVariant.get(variant.identifier()).add(score);
                if (rawAttempts.size() == manifest.stabilityRuns()) {
                    stabilityByVariant
                            .get(variant.identifier())
                            .add(new StabilityObservation(
                                    sample.sampleId(),
                                    rawAttempts.stream()
                                            .map(EvaluationRunner::signature)
                                            .toList()));
                }
                variantResults.put(
                        variant.identifier(),
                        new EvaluationVariantResult(variant.identifier(), score, List.copyOf(rawAttempts)));
            }
            sampleResults.add(new EvaluationSampleResult(sample.sampleId(), sample, Map.copyOf(variantResults)));
        }

        Map<String, CoreMetrics> metrics = new LinkedHashMap<>();
        variants.forEach(variant -> metrics.put(
                variant.identifier(),
                CoreMetrics.calculate(
                        scoresByVariant.get(variant.identifier()), stabilityByVariant.get(variant.identifier()))));
        return new EvaluationResult(
                manifest,
                variants.stream()
                        .map(variant -> new EvaluationVariantSummary(
                                variant.type(), variant.identifier(), variant.contentHash()))
                        .toList(),
                List.copyOf(sampleResults),
                Map.copyOf(metrics));
    }

    /** 一条样本的异步执行计划；Map 只保存对应变体的有序 attempt future，不承载额外领域状态。 */
    private record PlannedSample(
            GoldSample sample, Map<String, List<CompletableFuture<EvaluationAttemptResult>>> attemptsByVariant) {}

    private EvaluationAttemptResult execute(
            UUID evaluationId,
            UUID snapshotId,
            GoldSample sample,
            AgentVariant variant,
            RunManifest manifest,
            int attempt) {
        Instant started = Instant.now();
        try {
            JsonNode output = executor.execute(new EvaluationExecutionRequest(
                    evaluationId, snapshotId, UUID.randomUUID(), sample, variant, manifest, attempt));
            return new EvaluationAttemptResult(
                    attempt, output, Duration.between(started, Instant.now()).toMillis(), null);
        } catch (RuntimeException exception) {
            // 只保留 ServiceException 的稳定业务 code，既能区分超时、缺结果和 schema 失败，又不会把模型原文、
            // 内网地址或堆栈写入评测报告；未知异常仍收敛为通用失败码。
            String errorCode = exception instanceof ServiceException serviceException
                    ? serviceException.getCode()
                    : "VARIANT_EXECUTION_FAILED";
            if (exception instanceof ServiceException) {
                // ServiceException 的描述已经经过全局脱敏器。报告仍只持久化稳定 code，
                // 但本地验收日志保留样本、变体、尝试次数与 schema 路径，便于定位真实模型的结构化输出失败。
                LOGGER.warn(
                        "评测尝试失败 sampleId={} variant={} attempt={} code={} diagnostic={}",
                        sample.sampleId(),
                        variant.identifier(),
                        attempt,
                        errorCode,
                        exception.getMessage());
            } else {
                LOGGER.warn(
                        "评测尝试失败 sampleId={} variant={} attempt={} code={}",
                        sample.sampleId(),
                        variant.identifier(),
                        attempt,
                        errorCode);
            }
            return new EvaluationAttemptResult(
                    attempt, null, Duration.between(started, Instant.now()).toMillis(), errorCode);
        }
    }

    private static String signature(EvaluationAttemptResult attempt) {
        JsonNode claims = attempt.output() == null ? null : attempt.output().path("claims");
        if (attempt.errorCode() != null || claims == null || !claims.isArray() || claims.isEmpty()) {
            return "ERROR";
        }
        JsonNode claim = claims.get(0);
        return claim.path("subject").path("companyId").asText("UNRESOLVED")
                + ":"
                + claim.path("status").asText("INVALID");
    }

    private static void validateInputs(GoldDataset dataset, RunManifest manifest, List<AgentVariant> variants) {
        if (dataset == null
                || manifest == null
                || !dataset.contentHash().equals(manifest.datasetHash())
                || !dataset.samples().stream()
                        .map(GoldSample::sampleId)
                        .toList()
                        .equals(manifest.sampleIds())) {
            throw new ServiceException("EVALUATION_MANIFEST_MISMATCH", "数据集与运行清单不一致");
        }
        if (variants == null
                || variants.size() < 2
                || !"BASELINE".equals(variants.get(0).type())
                || variants.stream().map(AgentVariant::identifier).distinct().count() != variants.size()) {
            throw new ServiceException("EVALUATION_VARIANTS_INVALID", "评测必须从 BASELINE 开始并包含冻结 Skill");
        }
    }
}
