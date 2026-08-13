package com.hsmap.factverification.evaluation.manifest;

import java.util.List;
import java.util.Map;

/**
 * 一次评测的不可变同条件清单。
 *
 * <p>除变体指令或 Skill 内容外，所有字段在开始执行前固定；模型密钥和材料全文不属于清单。
 */
public record RunManifest(
        String datasetVersion,
        String datasetHash,
        List<String> sampleIds,
        Map<String, String> documentSnapshotHashes,
        String modelConfigHash,
        Map<String, Object> modelParameters,
        String agentRuntimeHash,
        String toolContractHash,
        String evidenceSnapshotHash,
        String outputSchemaHash,
        String baselineInstruction,
        String baselineInstructionHash,
        int timeoutSeconds,
        int stabilityRuns,
        String manifestHash) {}
