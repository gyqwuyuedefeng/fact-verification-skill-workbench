package com.hsmap.factverification.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.evaluation.dataset.GoldDataset;
import com.hsmap.factverification.evaluation.dataset.GoldEvidenceRequest;
import com.hsmap.factverification.shared.ServiceException;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 在模型评测开始前冻结数据集声明的全部只读证据请求。
 *
 * <p>三十条样本会重复查询同一家企业。这里按“工具名 + 规范参数 JSON”保序去重并顺序调用，使第一个变体开始前数据库已经存在完整快照；随后六线程模型评测只能从 MCP 重放同一响应，避免并发首次直查 ES 破坏同条件。
 */
@Component
public final class EvaluationEvidenceFreezer {

    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(70);

    private final EvidenceRunClientFactory clients;
    private final ObjectMapper objectMapper;

    public EvaluationEvidenceFreezer(EvidenceRunClientFactory clients, ObjectMapper objectMapper) {
        this.clients = clients;
        this.objectMapper = objectMapper;
    }

    /**
     * 为一次评测创建独占 MCP client，执行所有不重复请求并在返回前关闭连接。
     *
     * @param evaluationId 证据快照的评测所有者
     * @param snapshotId Run Manifest 已锁定的证据快照标识
     * @param dataset 声明主体查询和分类证据请求的冻结金标集
     */
    public void freeze(UUID evaluationId, UUID snapshotId, GoldDataset dataset) {
        Map<String, GoldEvidenceRequest> distinct = distinctRequests(dataset);
        try (EvidenceRunClient evidence = clients.open(snapshotId, "EVALUATION", evaluationId)) {
            int sequence = 0;
            for (GoldEvidenceRequest request : distinct.values()) {
                AgentTool tool = evidence.toolkit().getTool(request.toolName());
                if (tool == null || !tool.isReadOnly()) {
                    throw new ServiceException("EVALUATION_EVIDENCE_TOOL_INVALID", "评测证据请求只能调用已注册的只读工具");
                }
                Map<String, Object> arguments = arguments(request);
                ToolUseBlock use = new ToolUseBlock(
                        "evaluation-prefetch-" + (++sequence), request.toolName(), arguments);
                ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                                .toolUseBlock(use)
                                .input(arguments)
                                .build())
                        .block(TOOL_TIMEOUT);
                if (result == null
                        || result.isSuspended()
                        || result.getState() == ToolResultState.ERROR
                        || result.getState() == ToolResultState.DENIED
                        || result.getState() == ToolResultState.INTERRUPTED) {
                    throw new ServiceException("EVALUATION_EVIDENCE_PREFETCH_FAILED", "评测证据预取失败，禁止启动非同条件评测");
                }
            }
        }
    }

    /** 数据集顺序是冻结顺序；LinkedHashMap 只消除完全相同的请求，不重排不同工具调用。 */
    private Map<String, GoldEvidenceRequest> distinctRequests(GoldDataset dataset) {
        Map<String, GoldEvidenceRequest> requests = new LinkedHashMap<>();
        for (var sample : dataset.samples()) {
            for (GoldEvidenceRequest request : sample.evidenceRequests()) {
                try {
                    String key = request.toolName() + ":" + objectMapper.writeValueAsString(request.arguments());
                    requests.putIfAbsent(key, request);
                } catch (JsonProcessingException exception) {
                    throw new ServiceException("EVALUATION_EVIDENCE_REQUEST_INVALID", "评测证据请求无法规范化");
                }
            }
        }
        return requests;
    }

    /** 把装载后不可变的 JSON 参数转成 AgentScope 工具调用 Map，不增加或推断任何字段。 */
    private Map<String, Object> arguments(GoldEvidenceRequest request) {
        try {
            return objectMapper.convertValue(request.arguments(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IllegalArgumentException exception) {
            throw new ServiceException("EVALUATION_EVIDENCE_REQUEST_INVALID", "评测证据请求参数无法读取");
        }
    }
}
