package com.hsmap.factverification.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.evidence.EvaluationEvidenceFreezer;
import com.hsmap.factverification.evaluation.dataset.GoldDataset;
import com.hsmap.factverification.evaluation.dataset.GoldDatasetLoader;
import com.hsmap.factverification.evaluation.manifest.RunManifestFactory;
import com.hsmap.factverification.evaluation.persistence.EvaluationRunRepository;
import com.hsmap.factverification.evaluation.report.EvaluationReportGenerator;
import com.hsmap.factverification.persistence.JdbcJson;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.net.URI;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 被测试对象：{@link EvaluationService} 创建评测时的 Stable/Candidate 参评合同。
 * 测试目的：证明评测记录落库和后台线程启动前，服务已按当前生命周期状态校验精确变体组成与顺序。
 * 覆盖范围：已有 Stable 时缺失、错用历史版本或增加额外变体，以及首次建版时混入额外冻结版本。
 * 前置条件：金标、仓储和执行器均为本地替身；测试不连接数据库，也不启动模型、MCP 或真实评测线程工作。
 */
class EvaluationStableContractTest {

    private static final String DATASET_VERSION = "public-tech-2024-v3";

    /**
     * 测试场景：系统已有当前 Stable，但请求仍按首次建版只提交 BASELINE 与 Candidate。
     * 前置条件：仓储返回唯一 Stable 和一个冻结 Candidate。
     * 期望结果：创建在落库前以稳定业务错误拒绝。
     * 断言重点：当前 Stable 不能被发布阶段的晚校验替代。
     */
    @Test
    void rejectsEvaluationMissingCurrentStable() {
        UUID stableId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        SkillVersionRepository versions = versions(stableId, candidateId, null);

        assertInvalidVariants(versions, List.of("BASELINE", candidateId.toString()));
    }

    /**
     * 测试场景：请求包含一个历史归档版本而不是当前 Stable。
     * 前置条件：仓储同时存在唯一 Stable、ARCHIVED 历史版本和冻结 Candidate。
     * 期望结果：即使历史版本本身不可编辑，创建也必须拒绝。
     * 断言重点：第二个变体必须逐字等于当前 Stable 标识，不能只检查“已冻结”。
     */
    @Test
    void rejectsEvaluationUsingNonCurrentStable() {
        UUID stableId = UUID.randomUUID();
        UUID archivedId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        SkillVersionRepository versions = versions(stableId, candidateId, archivedId);

        assertInvalidVariants(
                versions, List.of("BASELINE", archivedId.toString(), candidateId.toString()));
    }

    /**
     * 测试场景：已有 Stable 的请求在标准三变体之外附加另一个冻结版本。
     * 前置条件：四个标识都能被旧实现解析为 BASELINE 或冻结 Skill。
     * 期望结果：服务拒绝多余变体，不创建无法解释门禁基准的评测。
     * 断言重点：合法顺序必须且只能是 BASELINE、当前 Stable、Candidate。
     */
    @Test
    void rejectsAdditionalVariantWhenStableExists() {
        UUID stableId = UUID.randomUUID();
        UUID extraId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        SkillVersionRepository versions = versions(stableId, candidateId, extraId);

        assertInvalidVariants(
                versions,
                List.of("BASELINE", stableId.toString(), extraId.toString(), candidateId.toString()));
    }

    /**
     * 测试场景：系统尚无 Stable，但首次建版请求混入两个冻结 Skill。
     * 前置条件：两个版本均为 Candidate，旧实现会把它们都解释为普通冻结变体。
     * 期望结果：服务只允许 BASELINE 与一个 Candidate 的两变体合同。
     * 断言重点：首次建版不能借额外变体伪装成普通三版本评测。
     */
    @Test
    void rejectsAdditionalVariantBeforeFirstStable() {
        UUID extraId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        SkillVersionRepository versions = versions(null, candidateId, extraId);

        assertInvalidVariants(versions, List.of("BASELINE", extraId.toString(), candidateId.toString()));
    }

    /** 统一断言稳定错误码，避免每个场景重复构造不会触碰真实基础设施的应用服务。 */
    private static void assertInvalidVariants(SkillVersionRepository versions, List<String> variantIds) {
        assertThatThrownBy(() -> service(versions)
                        .create("evaluation-contract-001", new EvaluationCreateCommand(DATASET_VERSION, variantIds)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("EVALUATION_VARIANTS_INVALID"));
    }

    /** 构造只完成创建前合同校验所需依赖的服务；后台仓储默认 markRunning=0，因此不会执行模型。 */
    private static EvaluationService service(SkillVersionRepository versions) {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        CanonicalJsonHasher hasher = new CanonicalJsonHasher(objectMapper);
        GoldDatasetLoader datasets = mock(GoldDatasetLoader.class);
        when(datasets.load(Path.of("evals/manifest.json")))
                .thenReturn(new GoldDataset(DATASET_VERSION, "fixture", "d".repeat(64), List.of()));
        EvaluationRunRepository evaluations = mock(EvaluationRunRepository.class);
        when(evaluations.findIdByRequestId("evaluation-contract-001")).thenReturn(Optional.empty());
        WorkbenchProperties properties = new WorkbenchProperties(
                new WorkbenchProperties.DatabaseBoundary("demo", "test", false),
                Path.of("data"),
                Path.of("evals/manifest.json"),
                Path.of("skills/company-material-fact-check"),
                new WorkbenchProperties.Model("http://model.example", "/v1/chat/completions", "model", "secret"),
                URI.create("http://127.0.0.1:19091/mcp"));
        return new EvaluationService(
                evaluations,
                versions,
                datasets,
                new RunManifestFactory(hasher),
                mock(EvaluationRunner.class),
                mock(EvaluationEvidenceFreezer.class),
                new EvaluationReportGenerator(objectMapper),
                properties,
                objectMapper,
                new JdbcJson(objectMapper),
                hasher);
    }

    /** 构造当前 Stable、Candidate 和可选额外冻结版本的完整页面投影与运行投影。 */
    private static SkillVersionRepository versions(UUID stableId, UUID candidateId, UUID extraId) {
        SkillVersionRepository repository = mock(SkillVersionRepository.class);
        java.util.ArrayList<SkillVersionRepository.VersionRow> rows = new java.util.ArrayList<>();
        if (stableId != null) {
            rows.add(row(stableId, "STABLE"));
            when(repository.findFrozen(stableId)).thenReturn(Optional.of(frozen(stableId, "STABLE")));
        }
        rows.add(row(candidateId, "CANDIDATE"));
        when(repository.findFrozen(candidateId)).thenReturn(Optional.of(frozen(candidateId, "CANDIDATE")));
        if (extraId != null) {
            rows.add(row(extraId, stableId == null ? "CANDIDATE" : "ARCHIVED"));
            when(repository.findFrozen(extraId))
                    .thenReturn(Optional.of(frozen(extraId, stableId == null ? "CANDIDATE" : "ARCHIVED")));
        }
        when(repository.listVersions()).thenReturn(List.copyOf(rows));
        return repository;
    }

    /** 生成创建合同只读取的冻结版本运行投影。 */
    private static SkillVersionRepository.FrozenVersion frozen(UUID id, String status) {
        return new SkillVersionRepository.FrozenVersion(id, "v-" + id, status, "a".repeat(64));
    }

    /** 生成当前生命周期列表投影，正文不会进入失败分支。 */
    private static SkillVersionRepository.VersionRow row(UUID id, String status) {
        OffsetDateTime now = OffsetDateTime.of(2026, 8, 14, 0, 0, 0, 0, ZoneOffset.UTC);
        return new SkillVersionRepository.VersionRow(
                id,
                null,
                "v-" + id,
                status,
                "# Skill",
                "[]",
                "[]",
                "{}",
                "a".repeat(64),
                "合同测试",
                null,
                null,
                "single-reviewer",
                now,
                now);
    }
}
