package com.hsmap.factverification.evaluation.manifest;

import com.hsmap.factverification.agent.AgentVariant;
import com.hsmap.factverification.agent.AgentRuntimeParameters;
import com.hsmap.factverification.evaluation.dataset.GoldDataset;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import com.hsmap.factverification.shared.ServiceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在评测开始前生成同条件清单和内容识别值。 */
public final class RunManifestFactory {

    private final CanonicalJsonHasher hasher;

    public RunManifestFactory(CanonicalJsonHasher hasher) {
        this.hasher = hasher;
    }

    /**
     * 创建清单；apiKey 参数只用于明确证明它被排除，不参与任何持久化对象或 hash。
     */
    public RunManifest create(
            GoldDataset dataset,
            String modelUrl,
            String modelId,
            String apiKey,
            String agentRuntimeVersion,
            String toolContractHash,
            String evidenceSnapshotHash,
            String outputSchemaHash,
            Map<String, String> documentSnapshotHashes,
            int timeoutSeconds) {
        if (dataset == null || timeoutSeconds <= 0) {
            throw new ServiceException("RUN_MANIFEST_INVALID", "评测数据集和超时必须有效");
        }
        Map<String, Object> modelParameters = AgentRuntimeParameters.manifestParameters();
        String modelConfigHash = hasher.hash(
                Map.of("url", value(modelUrl), "modelId", value(modelId), "parameters", modelParameters));
        String agentRuntimeHash = hasher.hash(value(agentRuntimeVersion));
        String baselineHash = hasher.hash(AgentVariant.BASELINE_INSTRUCTION);
        List<String> sampleIds =
                dataset.samples().stream().map(sample -> sample.sampleId()).toList();
        Map<String, String> documents =
                documentSnapshotHashes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(documentSnapshotHashes));
        Map<String, Object> hashInput = Map.ofEntries(
                Map.entry("datasetVersion", dataset.version()),
                Map.entry("datasetHash", dataset.contentHash()),
                Map.entry("sampleIds", sampleIds),
                Map.entry("documentSnapshotHashes", documents),
                Map.entry("modelConfigHash", modelConfigHash),
                Map.entry("modelParameters", modelParameters),
                Map.entry("agentRuntimeHash", agentRuntimeHash),
                Map.entry("toolContractHash", value(toolContractHash)),
                Map.entry("evidenceSnapshotHash", value(evidenceSnapshotHash)),
                Map.entry("outputSchemaHash", value(outputSchemaHash)),
                Map.entry("baselineInstructionHash", baselineHash),
                Map.entry("timeoutSeconds", timeoutSeconds),
                Map.entry("stabilityRuns", 3));
        return new RunManifest(
                dataset.version(),
                dataset.contentHash(),
                sampleIds,
                documents,
                modelConfigHash,
                modelParameters,
                agentRuntimeHash,
                value(toolContractHash),
                value(evidenceSnapshotHash),
                value(outputSchemaHash),
                AgentVariant.BASELINE_INSTRUCTION,
                baselineHash,
                timeoutSeconds,
                3,
                hasher.hash(hashInput));
    }

    private static String value(String value) {
        if (value == null || value.isBlank()) {
            throw new ServiceException("RUN_MANIFEST_FIELD_MISSING", "评测锁定条件缺少必填字段");
        }
        return value;
    }
}
