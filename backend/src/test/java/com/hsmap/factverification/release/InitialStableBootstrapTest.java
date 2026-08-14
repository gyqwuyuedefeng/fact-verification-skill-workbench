package com.hsmap.factverification.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hsmap.factverification.shared.ServiceException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证没有 Stable 时唯一允许的最小初始化路径。 */
class InitialStableBootstrapTest {

    private static final UUID VERSION_ID = UUID.fromString("87b7576f-6717-443e-af12-a4ecff326ffe");
    private static final UUID EVALUATION_ID = UUID.fromString("bd718fa4-061d-4165-876b-26035a7e8df7");

    private FakeBootstrapStore store;
    private InitialStableBootstrapService service;

    @BeforeEach
    void setUp() {
        store = new FakeBootstrapStore();
        service = new InitialStableBootstrapService(store);
    }

    /** 冻结 Candidate 且同条件评测包含 BASELINE 并门禁 PASS 时建立 revision 1。 */
    @Test
    void initializesFirstStableFromApprovedCandidate() {
        InitialStableResult result = service.initialize(VERSION_ID, EVALUATION_ID, "比赛初始 Stable", "reviewer");

        assertThat(result.revision()).isEqualTo(1);
        assertThat(result.stableVersionId()).isEqualTo(VERSION_ID);
        assertThat(store.appended).isTrue();
    }

    /** 已有发布事件时禁止重复初始化，后续只能走注册、晋升或回滚。 */
    @Test
    void rejectsRepeatedInitializationWhenStableExists() {
        store.hasCurrentRelease = true;

        assertThatThrownBy(() -> service.initialize(VERSION_ID, EVALUATION_ID, "重复初始化", "reviewer"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("INITIAL_STABLE_ALREADY_EXISTS");
    }

    /** DRAFT、门禁失败或没有通用基线变体都不能成为初始 Stable。 */
    @Test
    void rejectsCandidateThatDoesNotMeetBootstrapGate() {
        store.candidateStatus = "DRAFT";
        assertThatThrownBy(() -> service.initialize(VERSION_ID, EVALUATION_ID, "非法版本", "reviewer"))
                .isInstanceOf(ServiceException.class);

        store.candidateStatus = "CANDIDATE";
        store.gateStatus = "FAIL";
        assertThatThrownBy(() -> service.initialize(VERSION_ID, EVALUATION_ID, "门禁失败", "reviewer"))
                .isInstanceOf(ServiceException.class);

        store.gateStatus = "PASS";
        store.variantIdentifiers = Set.of(VERSION_ID.toString());
        assertThatThrownBy(() -> service.initialize(VERSION_ID, EVALUATION_ID, "缺少基线", "reviewer"))
                .isInstanceOf(ServiceException.class);
    }

    /**
     * 测试场景：首次建版试图使用三条快速 PASS 或伪装成正式版本的三条 PASS。
     * 前置条件：Candidate 已冻结、变体包含 BASELINE，隔离评测数据集资格缺陷。
     * 期望结果：两种记录都以 EVALUATION_NOT_RELEASE_ELIGIBLE 拒绝，初始发布事件不追加。
     * 断言重点：首版没有 Stable 也不能绕过正式三十条发布门禁。
     */
    @Test
    void rejectsNonFormalEvaluationForInitialStable() {
        store.datasetVersion = "public-tech-live-smoke-v1";
        store.sampleCount = 3;
        assertThatThrownBy(() -> service.initialize(VERSION_ID, EVALUATION_ID, "快速评测不能发布", "reviewer"))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo("EVALUATION_NOT_RELEASE_ELIGIBLE"));

        store.datasetVersion = "public-tech-2024-v3";
        store.sampleCount = 30;
        assertThatThrownBy(() -> service.initialize(VERSION_ID, EVALUATION_ID, "历史 v3 不能发布", "reviewer"))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo("EVALUATION_NOT_RELEASE_ELIGIBLE"));

        store.datasetVersion = "public-tech-2024-v4";
        store.sampleCount = 3;
        assertThatThrownBy(() -> service.initialize(VERSION_ID, EVALUATION_ID, "错误数量不能发布", "reviewer"))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo("EVALUATION_NOT_RELEASE_ELIGIBLE"));
        assertThat(store.appended).isFalse();
    }

    /** 测试替身只记录一次追加事件，不模拟范围外的通用工作流。 */
    private static final class FakeBootstrapStore implements InitialStableBootstrapStore {
        private boolean hasCurrentRelease;
        private boolean appended;
        private String candidateStatus = "CANDIDATE";
        private String datasetVersion = "public-tech-2024-v4";
        private int sampleCount = 30;
        private String gateStatus = "PASS";
        private Set<String> variantIdentifiers = Set.of("BASELINE", VERSION_ID.toString());

        @Override
        public Optional<BootstrapSkillVersion> findSkillVersion(UUID versionId) {
            return Optional.of(new BootstrapSkillVersion(versionId, candidateStatus, "a".repeat(64)));
        }

        @Override
        public Optional<BootstrapEvaluation> findEvaluation(UUID evaluationId) {
            return Optional.of(
                    new BootstrapEvaluation(evaluationId, datasetVersion, sampleCount, gateStatus, variantIdentifiers));
        }

        @Override
        public boolean releaseExists(String skillKey) {
            return hasCurrentRelease;
        }

        @Override
        public void appendInitialStable(UUID versionId, UUID evaluationId, String reason, String operator) {
            appended = true;
        }
    }
}
