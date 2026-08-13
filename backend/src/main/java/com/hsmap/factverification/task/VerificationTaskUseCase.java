package com.hsmap.factverification.task;

import java.util.List;
import java.util.UUID;

/** 任务 API 所需的最小应用服务边界。 */
public interface VerificationTaskUseCase {

    VerificationTaskView create(String requestId);

    VerificationTaskView upload(UUID taskId, String requestId, MaterialUpload material);

    /** 新普通入口只接受 BASELINE/STABLE。默认桥接仅用于旧测试替身。 */
    default VerificationTaskView start(UUID taskId, String requestId, String executionMode) {
        return start(taskId, requestId, false);
    }

    /** @deprecated 普通页面不再控制影子；保留到旧测试替身迁移完成。 */
    @Deprecated
    default VerificationTaskView start(UUID taskId, String requestId, boolean includeShadow) {
        throw new UnsupportedOperationException("旧影子参数已停用");
    }

    VerificationTaskView findTask(UUID taskId);

    List<VerificationClaimView> findPrimaryClaims(UUID taskId);

    List<VerificationClaimView> findRunClaims(UUID runId);

    List<RunEventView> replayEvents(UUID runId, String lastEventId);
}
