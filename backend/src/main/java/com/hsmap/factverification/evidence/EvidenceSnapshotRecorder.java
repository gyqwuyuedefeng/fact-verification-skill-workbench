package com.hsmap.factverification.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.evidence.persistence.EvidenceSnapshotRepository;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 在 Agent 后端记录 MCP 返回结果，保持 MCP Server 对比赛 PostgreSQL 只有 SELECT 权限。
 *
 * <p>AgentScope 的 MCP 工具通常直接返回最终 {@code ToolResultBlock}，不会经过可选的流式 chunk callback。因此这里在注册完成后
 * 用一个同名只读委托包装六个工具，在最终结果返回点记录快照；相同快照请求的数据库唯一键负责并发去重。
 */
@Component
public final class EvidenceSnapshotRecorder {

    private final EvidenceSnapshotRepository snapshots;
    private final ObjectMapper objectMapper;
    private final CanonicalJsonHasher hasher;

    public EvidenceSnapshotRecorder(
            EvidenceSnapshotRepository snapshots, ObjectMapper objectMapper, CanonicalJsonHasher hasher) {
        this.snapshots = snapshots;
        this.objectMapper = objectMapper;
        this.hasher = hasher;
    }

    /** 给一次运行的 Toolkit 安装最终结果记录器，不使用进程级可变 snapshot header。 */
    public void attach(Toolkit toolkit, UUID snapshotId, String ownerType, UUID ownerId) {
        for (String toolName : EVIDENCE_TOOLS) {
            AgentTool delegate = toolkit.getTool(toolName);
            if (delegate == null || delegate instanceof SnapshotRecordingTool) {
                continue;
            }
            toolkit.removeTool(toolName);
            toolkit.registerAgentTool(new SnapshotRecordingTool(delegate, snapshotId, ownerType, ownerId));
        }
    }

    /** 只记录批准的六个工具；Skill 内部装载工具等框架调用不进入企业证据快照。 */
    void record(UUID snapshotId, String ownerType, UUID ownerId, ToolUseBlock toolUse, ToolResultBlock toolResult) {
        if (!isEvidenceTool(toolUse.getName())) {
            return;
        }
        Map<String, Object> arguments = normalizeArguments(toolUse.getInput());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // AgentScope 2.0.1 的 MCP 适配器用无显式 state 的 ToolResultBlock 表示最终成功，构造器会把它
        // 归为 RUNNING；只有 error/denied/interrupted 才是明确失败。不能沿用“必须等于 SUCCESS”的判断，
        // 否则每条真实 MCP 成功响应都会被错误冻结成失败并在同一快照内反复重放。
        boolean success = !toolResult.isSuspended()
                && toolResult.getState() != ToolResultState.ERROR
                && toolResult.getState() != ToolResultState.DENIED
                && toolResult.getState() != ToolResultState.INTERRUPTED;
        JsonNode response = success ? responseNode(toolResult) : null;
        String errorCode = success ? null : "MCP_TOOL_FAILED";
        String responseHash =
                hasher.hash(success ? response : Map.of("errorCode", errorCode, "tool", toolUse.getName()));
        snapshots.append(new EvidenceSnapshotRepository.SnapshotRow(
                UUID.randomUUID(),
                snapshotId,
                ownerType,
                ownerId,
                toolUse.getName(),
                writeJson(arguments),
                hasher.hash(arguments),
                now,
                success ? writeJson(response) : null,
                errorCode,
                success ? null : "企业证据工具执行失败",
                responseHash,
                now));
    }

    private JsonNode responseNode(ToolResultBlock result) {
        if (result.getOutput().size() == 1 && result.getOutput().get(0) instanceof TextBlock text) {
            try {
                return objectMapper.readTree(text.getText());
            } catch (Exception ignored) {
                return objectMapper.valueToTree(Map.of("text", text.getText()));
            }
        }
        return objectMapper.valueToTree(result.getOutput());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new com.hsmap.factverification.shared.ServiceException(
                    "EVIDENCE_SNAPSHOT_SERIALIZATION_FAILED", "证据快照序列化失败");
        }
    }

    private static Map<String, Object> normalizeArguments(Map<String, Object> input) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        input.forEach((key, value) -> normalized.put(key, value instanceof String text ? text.strip() : value));
        return Map.copyOf(normalized);
    }

    private static boolean isEvidenceTool(String name) {
        return EVIDENCE_TOOLS.contains(name);
    }

    private static final List<String> EVIDENCE_TOOLS = List.of(
            "resolve_company",
            "get_company_profile",
            "get_company_financials",
            "get_company_intellectual_property",
            "get_company_risks",
            "get_company_relationships");

    /**
     * 保留 MCP 工具原有 schema、只读属性和调用行为，只在成功或失败的最终结果返回时追加不可变快照。
     *
     * <p>该包装只存在于一次运行独占的 Toolkit 内，不会改变 MCP client，也不会把快照标识暴露为模型参数。
     */
    private final class SnapshotRecordingTool implements AgentTool {

        private final AgentTool delegate;
        private final UUID snapshotId;
        private final String ownerType;
        private final UUID ownerId;

        private SnapshotRecordingTool(AgentTool delegate, UUID snapshotId, String ownerType, UUID ownerId) {
            this.delegate = delegate;
            this.snapshotId = snapshotId;
            this.ownerType = ownerType;
            this.ownerId = ownerId;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public String getDescription() {
            return delegate.getDescription();
        }

        @Override
        public Map<String, Object> getParameters() {
            return delegate.getParameters();
        }

        @Override
        public Boolean getStrict() {
            return delegate.getStrict();
        }

        @Override
        public Map<String, Object> getOutputSchema() {
            return delegate.getOutputSchema();
        }

        @Override
        public boolean isReadOnly() {
            return delegate.isReadOnly();
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
            return delegate.callAsync(call).doOnNext(result -> record(
                    snapshotId,
                    ownerType,
                    ownerId,
                    call.getToolUseBlock(),
                    result));
        }
    }
}
