package com.hsmap.factverification.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsmap.factverification.agent.FactVerificationAgentRunner;
import com.hsmap.factverification.agent.AgentOutputContract;
import com.hsmap.factverification.evaluation.dataset.GoldSample;
import com.hsmap.factverification.shared.ServiceException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 把金标材料投影成真实 Agent 输入；不会把 expected 结论泄漏给模型。 */
@Component
public final class AgentEvaluationExecutor implements EvaluationExecutionPort {

    private static final Pattern LINE_LOCATOR = Pattern.compile("^L([1-9][0-9]*)$");

    private final FactVerificationAgentRunner agents;
    private final ObjectMapper objectMapper;

    public AgentEvaluationExecutor(FactVerificationAgentRunner agents, ObjectMapper objectMapper) {
        this.agents = agents;
        this.objectMapper = objectMapper;
    }

    /** BASELINE 与 Skill 仅变体本身不同，材料、快照和输出元数据全部来自同一清单。 */
    @Override
    public com.fasterxml.jackson.databind.JsonNode execute(EvaluationExecutionRequest request) {
        return agents.run(
                request.runId(),
                request.evidenceSnapshotId(),
                "EVALUATION",
                request.evaluationId(),
                request.variant(),
                buildPrompt(request),
                event -> {},
                Duration.ofSeconds(request.manifest().timeoutSeconds()));
    }

    /**
     * 生成与正式结果 schema 同形的评测提示。
     *
     * <p>运行元数据必须和普通对话一样包含嵌套的 variant 对象，否则模型即使逐字复制也无法通过统一 schema。这里使用有序 Map
     * 只是保证提示与识别值可复查，不在其中放入金标状态或人工证据。
     */
    String buildPrompt(EvaluationExecutionRequest request) {
        try {
            String documentHash = request.manifest()
                    .documentSnapshotHashes()
                    .getOrDefault(
                            request.sample().sampleId(), request.manifest().datasetHash());
            Map<String, Object> variantMetadata = new LinkedHashMap<>();
            variantMetadata.put("type", request.variant().type());
            variantMetadata.put("identifier", request.variant().identifier());
            variantMetadata.put("contentHash", request.variant().contentHash());
            Map<String, Object> runMetadata = new LinkedHashMap<>();
            runMetadata.put("runId", request.runId());
            runMetadata.put("variant", variantMetadata);
            runMetadata.put("documentSnapshotHash", documentHash);
            runMetadata.put("evidenceSnapshotId", request.evidenceSnapshotId());
            String prompt =
                    """
                    核验下面这一条材料主张。不要参考任何金标答案，只使用材料位置与企业证据工具。
                    %s
                    运行元数据：%s
                    材料快照：%s
                    """
                            .formatted(
                                    AgentOutputContract.instruction(),
                                    objectMapper.writeValueAsString(runMetadata),
                                    objectMapper.writeValueAsString(materialForModel(request.sample())));
            return prompt;
        } catch (JsonProcessingException exception) {
            throw new ServiceException("EVALUATION_PROMPT_INVALID", "评测材料无法序列化");
        }
    }

    /**
     * 把金标中便于审核者阅读的 `LINE/Ln` 定位投影为结果 schema 使用的解析器 locator。
     *
     * <p>该投影只改变模型输入的定位表示，不读取 expectedStatus、manualEvidence 等答案字段；同一样本、所有变体和所有重试得到完全相同的
     * fileId/行号。无法确定性转换时失败关闭，避免模型面对与输出 schema 冲突的输入结构。
     */
    static JsonNode materialForModel(GoldSample sample) {
        if (sample == null || !(sample.material().deepCopy() instanceof ObjectNode material)) {
            throw new ServiceException("EVALUATION_MATERIAL_INVALID", "评测材料必须是 JSON 对象");
        }
        JsonNode locator = material.path("locator");
        if (locator.hasNonNull("fileId")) {
            return material;
        }
        Matcher line = LINE_LOCATOR.matcher(locator.path("value").asText(""));
        if (!"LINE".equals(locator.path("type").asText()) || !line.matches()) {
            throw new ServiceException("EVALUATION_LOCATOR_INVALID", "评测材料定位无法转换为统一 locator");
        }
        int lineNumber = Integer.parseInt(line.group(1));
        ObjectNode normalized = material.objectNode();
        normalized.put("fileId", sample.sampleId());
        normalized.put("lineStart", lineNumber);
        normalized.put("lineEnd", lineNumber);
        material.set("locator", normalized);
        return material;
    }
}
