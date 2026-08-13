package com.hsmap.factverification.release.persistence;

import com.hsmap.factverification.evaluation.persistence.EvaluationRunRepository;
import com.hsmap.factverification.release.BootstrapEvaluation;
import com.hsmap.factverification.release.BootstrapSkillVersion;
import com.hsmap.factverification.release.InitialStableBootstrapStore;
import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** 用现有三张表实现初始 Stable 存储端口，不复制发布门禁业务判断。 */
@Repository
public class InitialStableBootstrapJdbcStore implements InitialStableBootstrapStore {

    private final SkillVersionRepository skillVersions;
    private final EvaluationRunRepository evaluations;
    private final ReleaseBindingRepository releases;

    public InitialStableBootstrapJdbcStore(
            SkillVersionRepository skillVersions,
            EvaluationRunRepository evaluations,
            ReleaseBindingRepository releases) {
        this.skillVersions = skillVersions;
        this.evaluations = evaluations;
        this.releases = releases;
    }

    @Override
    public Optional<BootstrapSkillVersion> findSkillVersion(UUID versionId) {
        return skillVersions.findBootstrap(versionId);
    }

    @Override
    public Optional<BootstrapEvaluation> findEvaluation(UUID evaluationId) {
        return evaluations.findBootstrap(evaluationId);
    }

    @Override
    public boolean releaseExists(String skillKey) {
        return releases.exists(skillKey);
    }

    /** 标记版本和追加 revision 1 由上层事务包裹，任一步失败都会整体回滚。 */
    @Override
    public void appendInitialStable(UUID versionId, UUID evaluationId, String reason, String operator) {
        if (skillVersions.markStable(versionId) != 1) {
            throw new ServiceException("INITIAL_SKILL_STATE_CHANGED", "初始版本状态已变化，请刷新后重试");
        }
        String stateAfter =
                "{\"stableVersionId\":\"" + versionId + "\",\"candidateVersionId\":null,\"shadowEnabled\":false}";
        releases.append(new ReleaseBindingRepository.ReleaseEvent(
                UUID.randomUUID(),
                "company-material-fact-check",
                1L,
                "INITIALIZE",
                versionId,
                null,
                null,
                false,
                evaluationId,
                "{}",
                stateAfter,
                reason,
                operator,
                OffsetDateTime.now(ZoneOffset.UTC)));
    }
}
