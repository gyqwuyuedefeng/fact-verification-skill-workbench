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

    /** 测试替身只记录一次追加事件，不模拟范围外的通用工作流。 */
    private static final class FakeBootstrapStore implements InitialStableBootstrapStore {
        private boolean hasCurrentRelease;
        private boolean appended;
        private String candidateStatus = "CANDIDATE";
        private String gateStatus = "PASS";
        private Set<String> variantIdentifiers = Set.of("BASELINE", VERSION_ID.toString());

        @Override
        public Optional<BootstrapSkillVersion> findSkillVersion(UUID versionId) {
            return Optional.of(new BootstrapSkillVersion(versionId, candidateStatus, "a".repeat(64)));
        }

        @Override
        public Optional<BootstrapEvaluation> findEvaluation(UUID evaluationId) {
            return Optional.of(new BootstrapEvaluation(evaluationId, gateStatus, variantIdentifiers));
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
