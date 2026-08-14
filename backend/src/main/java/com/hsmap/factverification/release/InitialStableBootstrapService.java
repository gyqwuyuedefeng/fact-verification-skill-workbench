package com.hsmap.factverification.release;

import com.hsmap.factverification.evaluation.dataset.GoldDatasetLoader;
import com.hsmap.factverification.shared.ServiceException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在系统没有 Stable 时建立唯一初始版本。
 *
 * <p>这不是通用状态机：它只实现比赛所需的一次性 INITIALIZE，后续注册、影子、晋升和回滚由发布服务处理。
 */
@Service
public class InitialStableBootstrapService {

    public static final String SKILL_KEY = "company-material-fact-check";

    private final InitialStableBootstrapStore store;

    /** 注入最小存储端口，便于在不连接共享测试库的情况下验证门禁。 */
    public InitialStableBootstrapService(InitialStableBootstrapStore store) {
        this.store = store;
    }

    /** 检查冻结版本、BASELINE 对照和 PASS 门禁后，原子追加首条发布事件。 */
    @Transactional
    public InitialStableResult initialize(UUID versionId, UUID evaluationId, String reason, String operator) {
        if (store.releaseExists(SKILL_KEY)) {
            throw new ServiceException("INITIAL_STABLE_ALREADY_EXISTS", "已有 Stable，禁止重复初始化");
        }
        BootstrapSkillVersion version = store.findSkillVersion(versionId)
                .orElseThrow(() -> new ServiceException("SKILL_VERSION_NOT_FOUND", "待初始化 Skill 版本不存在"));
        if (!"CANDIDATE".equals(version.status())
                || version.contentHash() == null
                || version.contentHash().length() != 64) {
            throw new ServiceException("INITIAL_SKILL_NOT_FROZEN", "初始 Stable 必须来自已冻结 Candidate");
        }
        BootstrapEvaluation evaluation = store.findEvaluation(evaluationId)
                .orElseThrow(() -> new ServiceException("EVALUATION_NOT_FOUND", "初始发布评测不存在"));
        if (!GoldDatasetLoader.FORMAL_DATASET_VERSION.equals(evaluation.datasetVersion())
                || evaluation.sampleCount() != GoldDatasetLoader.MIN_GATE_SAMPLE_COUNT) {
            throw new ServiceException("EVALUATION_NOT_RELEASE_ELIGIBLE", "只有正式 30 条评测可以用于版本发布");
        }
        if (!"PASS".equals(evaluation.gateStatus())
                || !evaluation.variantIdentifiers().contains("BASELINE")
                || !evaluation.variantIdentifiers().contains(versionId.toString())) {
            throw new ServiceException("INITIAL_EVALUATION_GATE_FAILED", "初始 Stable 必须通过包含 BASELINE 的同条件评测");
        }
        if (reason == null || reason.isBlank() || operator == null || operator.isBlank()) {
            throw new ServiceException("RELEASE_AUDIT_REQUIRED", "发布原因和操作人不能为空");
        }
        store.appendInitialStable(versionId, evaluationId, reason, operator);
        return new InitialStableResult(1, versionId);
    }
}
