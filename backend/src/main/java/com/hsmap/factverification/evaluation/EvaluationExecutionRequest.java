package com.hsmap.factverification.evaluation;

import com.hsmap.factverification.agent.AgentVariant;
import com.hsmap.factverification.evaluation.dataset.GoldSample;
import com.hsmap.factverification.evaluation.manifest.RunManifest;
import java.util.UUID;

/** 一次独立样本执行需要的全部锁定输入。 */
public record EvaluationExecutionRequest(
        UUID evaluationId,
        UUID evidenceSnapshotId,
        UUID runId,
        GoldSample sample,
        AgentVariant variant,
        RunManifest manifest,
        int attempt) {}
