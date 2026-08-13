package com.hsmap.factverification.release;

import java.util.Optional;
import java.util.UUID;

/** 初始发布服务访问现有三张表所需的最小存储端口。 */
public interface InitialStableBootstrapStore {

    /** 查询待发布冻结版本。 */
    Optional<BootstrapSkillVersion> findSkillVersion(UUID versionId);

    /** 查询版本所依据的同条件评测门禁。 */
    Optional<BootstrapEvaluation> findEvaluation(UUID evaluationId);

    /** 判断固定 Skill 家族是否已经产生过发布事件。 */
    boolean releaseExists(String skillKey);

    /** 在同一事务追加 revision 1 INITIALIZE 事件并把版本标记为 STABLE。 */
    void appendInitialStable(UUID versionId, UUID evaluationId, String reason, String operator);
}
