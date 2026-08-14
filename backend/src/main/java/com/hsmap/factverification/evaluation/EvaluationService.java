package com.hsmap.factverification.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.agent.AgentVariant;
import com.hsmap.factverification.agent.FactVerificationAgentFactory;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.evaluation.dataset.GoldDataset;
import com.hsmap.factverification.evaluation.dataset.GoldDatasetLoader;
import com.hsmap.factverification.evaluation.gate.CandidateGate;
import com.hsmap.factverification.evaluation.gate.GateInput;
import com.hsmap.factverification.evaluation.gate.GateResult;
import com.hsmap.factverification.evaluation.manifest.RunManifest;
import com.hsmap.factverification.evaluation.manifest.RunManifestFactory;
import com.hsmap.factverification.evaluation.persistence.EvaluationRunRepository;
import com.hsmap.factverification.evaluation.report.EvaluationReport;
import com.hsmap.factverification.evaluation.report.EvaluationReportGenerator;
import com.hsmap.factverification.evaluation.scoring.CoreMetrics;
import com.hsmap.factverification.evidence.EvaluationEvidenceFreezer;
import com.hsmap.factverification.persistence.JdbcJson;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 同条件评测应用服务。
 *
 * <p>单人 MVP 只启动一个本地后台线程，不引入队列和调度服务；所有可恢复资产在调用模型前先写入 PostgreSQL。
 */
@Service
public final class EvaluationService implements EvaluationUseCase {

    private static final String OPERATOR = "single-reviewer";
    private static final List<String> TOOL_NAMES = List.of(
            "resolve_company",
            "get_company_profile",
            "get_company_financials",
            "get_company_intellectual_property",
            "get_company_risks",
            "get_company_relationships");

    private final EvaluationRunRepository evaluations;
    private final SkillVersionRepository skillVersions;
    private final GoldDatasetLoader datasets;
    private final RunManifestFactory manifests;
    private final EvaluationRunner runner;
    private final EvaluationEvidenceFreezer evidenceFreezer;
    private final EvaluationReportGenerator reports;
    private final WorkbenchProperties properties;
    private final ObjectMapper objectMapper;
    private final JdbcJson jdbcJson;
    private final CanonicalJsonHasher hasher;

    public EvaluationService(
            EvaluationRunRepository evaluations,
            SkillVersionRepository skillVersions,
            GoldDatasetLoader datasets,
            RunManifestFactory manifests,
            EvaluationRunner runner,
            EvaluationEvidenceFreezer evidenceFreezer,
            EvaluationReportGenerator reports,
            WorkbenchProperties properties,
            ObjectMapper objectMapper,
            JdbcJson jdbcJson,
            CanonicalJsonHasher hasher) {
        this.evaluations = evaluations;
        this.skillVersions = skillVersions;
        this.datasets = datasets;
        this.manifests = manifests;
        this.runner = runner;
        this.evidenceFreezer = evidenceFreezer;
        this.reports = reports;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jdbcJson = jdbcJson;
        this.hasher = hasher;
    }

    /** 按幂等键创建不可变清单，随后异步执行固定的三条快速或三十条正式对照。 */
    @Override
    public EvaluationRunView create(String requestId, EvaluationCreateCommand command) {
        UUID existing = evaluations.findIdByRequestId(requestId).orElse(null);
        if (existing != null) {
            return get(existing);
        }
        int minimumSampleCount = minimumSampleCount(command.datasetVersion());
        GoldDataset dataset = loadDataset(command.datasetVersion());
        if (!dataset.version().equals(command.datasetVersion())) {
            throw new ServiceException("DATASET_VERSION_NOT_FOUND", "仅允许使用已冻结的比赛金标版本");
        }
        List<AgentVariant> variants = resolveVariants(command.variantIds());
        UUID evaluationId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        RunManifest manifest = createManifest(dataset, snapshotId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        evaluations.insertPending(new EvaluationRunRepository.NewEvaluation(
                evaluationId,
                requestId,
                dataset.version(),
                dataset.contentHash(),
                dataset.samples().size(),
                snapshotId,
                jdbcJson.write(manifest),
                jdbcJson.write(variants.stream()
                        .map(variant -> new EvaluationVariantSummary(
                                variant.type(), variant.identifier(), variant.contentHash()))
                        .toList()),
                OPERATOR,
                now));
        Thread worker = new Thread(
                () -> execute(evaluationId, snapshotId, dataset, manifest, variants, minimumSampleCount),
                "evaluation-" + evaluationId);
        worker.setDaemon(true);
        worker.start();
        return EvaluationRunView.pending(
                evaluationId, dataset.version(), dataset.samples().size());
    }

    @Override
    public EvaluationRunView get(UUID evaluationId) {
        EvaluationRunRepository.EvaluationRow row = find(evaluationId);
        return new EvaluationRunView(
                row.id(),
                row.datasetVersion(),
                row.datasetHash(),
                row.sampleCount(),
                readObject(row.variantsJson()),
                readObject(row.runManifestJson()),
                readObject(row.metricsJson()),
                row.status(),
                row.gateStatus(),
                readObject(row.gateReasonsJson()),
                row.createdAt(),
                row.finishedAt());
    }

    /** 历史列表只投影原始评测；可选版本筛选通过参评清单完成。 */
    @Override
    public List<EvaluationRunView> list(UUID versionId) {
        String identifier = versionId == null ? null : versionId.toString();
        return evaluations.list().stream()
                .filter(row -> identifier == null || variantIdentifiers(row).contains(identifier))
                .map(this::toView)
                .toList();
    }

    /** 汇总保留每个原始批次，并以版本记录中的注册 ID 标出正式门禁评测。 */
    @Override
    public SkillEvaluationSummary versionSummary(UUID versionId) {
        List<EvaluationRunView> history = list(versionId);
        UUID registered = skillVersions
                .findVersion(versionId)
                .map(SkillVersionRepository.VersionRow::registeredEvaluationId)
                .orElse(null);
        return new SkillEvaluationSummary(
                versionId,
                history.size(),
                history.isEmpty() ? null : history.get(0).id(),
                registered,
                history);
    }

    /** 直接优劣只取同时包含两个版本的最新同批次评测，天然共享全部锁定条件。 */
    @Override
    public EvaluationComparison compare(UUID leftVersionId, UUID rightVersionId) {
        String left = leftVersionId.toString();
        String right = rightVersionId.toString();
        EvaluationRunRepository.EvaluationRow shared = evaluations.list().stream()
                .filter(row -> "COMPLETED".equals(row.status()))
                .filter(row -> variantIdentifiers(row).containsAll(List.of(left, right)))
                .findFirst()
                .orElse(null);
        if (shared == null) {
            return new EvaluationComparison(
                    false,
                    leftVersionId,
                    rightVersionId,
                    null,
                    List.of("两个版本没有共同的已完成同条件评测"),
                    Map.of(),
                    Map.of(),
                    Map.of());
        }
        return new EvaluationComparison(
                true,
                leftVersionId,
                rightVersionId,
                shared.id(),
                List.of(),
                metricDeltas(shared.metricsJson(), left, right),
                sampleOutcomes(shared.sampleResultsJson(), left, right),
                failureTypeChanges(shared.failuresJson(), left, right));
    }

    @Override
    public List<Map<String, Object>> samples(UUID evaluationId) {
        String json = find(evaluationId).sampleResultsJson();
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException exception) {
            throw new ServiceException("EVALUATION_RESULT_INVALID", "评测样本结果无法读取");
        }
    }

    @Override
    public void review(UUID evaluationId, String requestId, EvaluationReviewCommand command) {
        find(evaluationId);
        Map<String, Object> correction = new LinkedHashMap<>();
        correction.put("requestId", requestId);
        correction.put("sampleId", command.sampleId());
        correction.put("variantId", command.variantId());
        correction.put("before", command.before());
        correction.put("after", command.after());
        correction.put("reason", command.reason());
        correction.put("operator", OPERATOR);
        correction.put("createdAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        evaluations.appendHumanCorrection(evaluationId, requestId, jdbcJson.write(correction));
    }

    @Override
    public EvaluationReport report(UUID evaluationId) {
        EvaluationRunRepository.EvaluationRow row = find(evaluationId);
        if (row.reportJson() == null || row.reportMarkdown() == null) {
            throw new ServiceException("EVALUATION_REPORT_NOT_READY", "评测报告尚未生成");
        }
        try {
            return new EvaluationReport(row.reportMarkdown(), objectMapper.readTree(row.reportJson()));
        } catch (JsonProcessingException exception) {
            throw new ServiceException("EVALUATION_REPORT_INVALID", "评测报告无法读取");
        }
    }

    private void execute(
            UUID evaluationId,
            UUID snapshotId,
            GoldDataset dataset,
            RunManifest manifest,
            List<AgentVariant> variants,
            int minimumSampleCount) {
        try {
            if (evaluations.markRunning(evaluationId) != 1) {
                return;
            }
            // 快照必须在任何 BASELINE/Skill 模型线程启动前完整冻结；否则并发首次查询可能各自命中变化中的 ES。
            evidenceFreezer.freeze(evaluationId, snapshotId, dataset);
            EvaluationResult result = runner.run(evaluationId, snapshotId, dataset, manifest, variants);
            GateResult gate = gate(result, variants, minimumSampleCount);
            EvaluationReport report = reports.generate(evaluationId, result, gate);
            List<Map<String, String>> failures = failures(result, variants);
            evaluations.complete(new EvaluationRunRepository.CompletedEvaluation(
                    evaluationId,
                    jdbcJson.write(result.sampleResults()),
                    jdbcJson.write(result.metrics()),
                    jdbcJson.write(failures),
                    gate.status(),
                    jdbcJson.write(gate.checks()),
                    report.markdown(),
                    jdbcJson.write(report.json()),
                    OffsetDateTime.now(ZoneOffset.UTC)));
        } catch (RuntimeException exception) {
            evaluations.markFailed(evaluationId);
        }
    }

    private GateResult gate(EvaluationResult result, List<AgentVariant> variants, int minimumSampleCount) {
        String candidate = variants.get(variants.size() - 1).identifier();
        String stable = variants.get(variants.size() - 2).identifier();
        List<String> fixed = new ArrayList<>();
        List<String> newFailures = new ArrayList<>();
        result.sampleResults().forEach(sample -> {
            boolean stableAccurate = sample.variantResults().get(stable).score().accurate();
            boolean candidateAccurate =
                    sample.variantResults().get(candidate).score().accurate();
            if (!stableAccurate && candidateAccurate) {
                fixed.add(sample.sampleId());
            }
            if (stableAccurate && !candidateAccurate) {
                newFailures.add("NEW_SCORING_FAILURE:" + sample.sampleId());
            }
        });
        CoreMetrics stableMetrics = result.metrics().get(stable);
        CoreMetrics candidateMetrics = result.metrics().get(candidate);
        return new CandidateGate()
                .evaluate(
                        new GateInput(
                                result.manifest().sampleIds().size(),
                                stableMetrics,
                                candidateMetrics,
                                List.of(),
                                fixed,
                                newFailures,
                                true),
                        minimumSampleCount);
    }

    /**
     * 请求只能命中两个版本控制中的固定清单，不接受前端传入路径或任意数据集版本。
     */
    private GoldDataset loadDataset(String datasetVersion) {
        if (GoldDatasetLoader.FORMAL_DATASET_VERSION.equals(datasetVersion)) {
            return datasets.load(properties.evaluationManifest());
        }
        if (GoldDatasetLoader.LIVE_SMOKE_DATASET_VERSION.equals(datasetVersion)) {
            return datasets.load(properties.quickEvaluationManifest(), GoldDatasetLoader.LIVE_SMOKE_SAMPLE_COUNT);
        }
        throw new ServiceException("DATASET_VERSION_NOT_FOUND", "仅允许使用已冻结的比赛金标版本");
    }

    /** 返回数据集对应的固定门禁分母下限，未知版本在读取任何文件前失败关闭。 */
    private static int minimumSampleCount(String datasetVersion) {
        if (GoldDatasetLoader.FORMAL_DATASET_VERSION.equals(datasetVersion)) {
            return GoldDatasetLoader.MIN_GATE_SAMPLE_COUNT;
        }
        if (GoldDatasetLoader.LIVE_SMOKE_DATASET_VERSION.equals(datasetVersion)) {
            return GoldDatasetLoader.LIVE_SMOKE_SAMPLE_COUNT;
        }
        throw new ServiceException("DATASET_VERSION_NOT_FOUND", "仅允许使用已冻结的比赛金标版本");
    }

    private static List<Map<String, String>> failures(EvaluationResult result, List<AgentVariant> variants) {
        String candidate = variants.get(variants.size() - 1).identifier();
        return result.sampleResults().stream()
                .filter(sample ->
                        !sample.variantResults().get(candidate).score().accurate())
                .map(sample -> Map.of(
                        "sampleId", sample.sampleId(), "variantId", candidate, "failureType", "GOLD_SCORING_MISMATCH"))
                .toList();
    }

    private RunManifest createManifest(GoldDataset dataset, UUID snapshotId) {
        Map<String, String> materialHashes = new LinkedHashMap<>();
        // Run Manifest 必须锁定模型实际看到的确定性材料投影，而不是只锁定内部金标使用的 LINE/Ln 简写。
        dataset.samples()
                .forEach(sample -> materialHashes.put(
                        sample.sampleId(), hasher.hash(AgentEvaluationExecutor.materialForModel(sample))));
        WorkbenchProperties.Model model = properties.model();
        return manifests.create(
                dataset,
                model.url(),
                model.id(),
                model.apiKey(),
                FactVerificationAgentFactory.RUNTIME_IDENTITY,
                hasher.hash(TOOL_NAMES),
                hasher.hash(Map.of("snapshotId", snapshotId, "mode", "immutable-replay")),
                outputSchemaHash(),
                materialHashes,
                120);
    }

    private List<AgentVariant> resolveVariants(List<String> identifiers) {
        SkillVersionRepository.VersionRow currentStable = currentStable();
        int expectedSize = currentStable == null ? 2 : 3;
        if (identifiers == null
                || identifiers.size() != expectedSize
                || !"BASELINE".equals(identifiers.get(0))
                || (currentStable != null && !currentStable.id().toString().equals(identifiers.get(1)))) {
            throw invalidVariants(currentStable != null);
        }
        List<AgentVariant> variants = new ArrayList<>();
        variants.add(AgentVariant.baseline(hasher.hash(AgentVariant.BASELINE_INSTRUCTION)));
        if (currentStable != null) {
            variants.add(resolveVariant(identifiers.get(1), "STABLE", true));
        }
        variants.add(resolveVariant(identifiers.get(identifiers.size() - 1), "CANDIDATE", currentStable != null));
        return List.copyOf(variants);
    }

    /**
     * 从生命周期列表确定唯一当前 Stable。
     *
     * <p>单 Skill MVP 允许零个或一个 Stable；若持久化状态已经出现多个 Stable，创建评测必须失败关闭，不能任意选择其中一个作为门禁基准。
     */
    private SkillVersionRepository.VersionRow currentStable() {
        List<SkillVersionRepository.VersionRow> stableVersions = skillVersions.listVersions().stream()
                .filter(version -> "STABLE".equals(version.status()))
                .toList();
        if (stableVersions.size() > 1) {
            throw new ServiceException("EVALUATION_VARIANTS_INVALID", "当前 Stable 状态异常，不能创建评测");
        }
        return stableVersions.isEmpty() ? null : stableVersions.get(0);
    }

    /**
     * 按固定位置解析冻结版本并核对生命周期角色。
     *
     * <p>“已冻结”只能证明内容不可变，不能证明它是当前 Stable 或待评测 Candidate；因此这里还必须核对状态和当前 Stable 标识。
     */
    private AgentVariant resolveVariant(String identifier, String expectedStatus, boolean stableExists) {
        UUID id;
        try {
            id = UUID.fromString(identifier);
        } catch (IllegalArgumentException exception) {
            throw invalidVariants(stableExists);
        }
        SkillVersionRepository.FrozenVersion version =
                skillVersions.findFrozen(id).orElseThrow(() -> invalidVariants(stableExists));
        if (!expectedStatus.equals(version.status())) {
            throw invalidVariants(stableExists);
        }
        Path runtimeRoot = properties.storageRoot().resolve("skill-runtime").resolve(id.toString());
        return AgentVariant.skill(id.toString(), version.contentHash(), runtimeRoot);
    }

    /** 返回不暴露版本 ID 的稳定合同错误；消息明确区分首次建版与已有 Stable 两种固定组成。 */
    private static ServiceException invalidVariants(boolean stableExists) {
        String expected =
                stableExists ? "已有 Stable 时评测必须且只能包含 BASELINE、当前 Stable、Candidate" : "首次建版评测必须且只能包含 BASELINE、Candidate";
        return new ServiceException("EVALUATION_VARIANTS_INVALID", expected);
    }

    private String outputSchemaHash() {
        try (InputStream input = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("schemas/verification-result.schema.json")) {
            if (input == null) {
                throw new ServiceException("OUTPUT_SCHEMA_MISSING", "统一输出 schema 不存在");
            }
            return hasher.hash(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new ServiceException("OUTPUT_SCHEMA_INVALID", "统一输出 schema 无法读取");
        }
    }

    private EvaluationRunRepository.EvaluationRow find(UUID evaluationId) {
        return evaluations
                .find(evaluationId)
                .orElseThrow(() -> new ServiceException("EVALUATION_NOT_FOUND", "评测记录不存在"));
    }

    private EvaluationRunView toView(EvaluationRunRepository.EvaluationRow row) {
        return new EvaluationRunView(
                row.id(),
                row.datasetVersion(),
                row.datasetHash(),
                row.sampleCount(),
                readObject(row.variantsJson()),
                readObject(row.runManifestJson()),
                readObject(row.metricsJson()),
                row.status(),
                row.gateStatus(),
                readObject(row.gateReasonsJson()),
                row.createdAt(),
                row.finishedAt());
    }

    private List<String> variantIdentifiers(EvaluationRunRepository.EvaluationRow row) {
        if (row.variantsJson() == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> variants = objectMapper.readValue(row.variantsJson(), new TypeReference<>() {});
            return variants.stream()
                    .map(item -> String.valueOf(item.get("identifier")))
                    .toList();
        } catch (JsonProcessingException exception) {
            throw new ServiceException("PERSISTED_JSON_INVALID", "评测参评版本清单无法读取");
        }
    }

    private Map<String, Double> metricDeltas(String json, String left, String right) {
        Map<String, Object> metrics = readMap(json);
        Map<String, Object> leftMetrics = asMap(metrics.get(left));
        Map<String, Object> rightMetrics = asMap(metrics.get(right));
        Map<String, Double> result = new LinkedHashMap<>();
        for (String key : List.of("accuracy", "completionRate", "stability", "humanInterventionRate")) {
            double delta = number(asMap(rightMetrics.get(key)).get("value"))
                    - number(asMap(leftMetrics.get(key)).get("value"));
            result.put(key, Math.round(delta * 10_000d) / 10_000d);
        }
        return Map.copyOf(result);
    }

    private Map<String, Integer> sampleOutcomes(String json, String left, String right) {
        int leftWins = 0;
        int rightWins = 0;
        int ties = 0;
        for (Map<String, Object> sample : readListOfMaps(json)) {
            Map<String, Object> variants = asMap(sample.get("variantResults"));
            boolean leftAccurate =
                    bool(asMap(asMap(variants.get(left)).get("score")).get("accurate"));
            boolean rightAccurate =
                    bool(asMap(asMap(variants.get(right)).get("score")).get("accurate"));
            if (leftAccurate == rightAccurate) {
                ties++;
            } else if (rightAccurate) {
                rightWins++;
            } else {
                leftWins++;
            }
        }
        return Map.of("leftWins", leftWins, "rightWins", rightWins, "ties", ties);
    }

    private Map<String, Integer> failureTypeChanges(String json, String left, String right) {
        int leftFailures = 0;
        int rightFailures = 0;
        for (Map<String, Object> failure : readListOfMaps(json)) {
            String variant = String.valueOf(failure.get("variantId"));
            if (left.equals(variant)) {
                leftFailures++;
            } else if (right.equals(variant)) {
                rightFailures++;
            }
        }
        return Map.of("leftFailures", leftFailures, "rightFailures", rightFailures);
    }

    private Map<String, Object> readMap(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new ServiceException("PERSISTED_JSON_INVALID", "评测指标无法读取");
        }
    }

    private List<Map<String, Object>> readListOfMaps(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new ServiceException("PERSISTED_JSON_INVALID", "评测明细无法读取");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0d;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private Object readObject(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("PERSISTED_JSON_INVALID", "评测持久化内容无法读取");
        }
    }
}
