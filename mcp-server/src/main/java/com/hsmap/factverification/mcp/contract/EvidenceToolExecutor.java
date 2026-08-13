package com.hsmap.factverification.mcp.contract;

import com.hsmap.factverification.mcp.query.LiveEvidenceQuery;
import com.hsmap.factverification.mcp.query.SnapshotReplayLookup;
import com.hsmap.factverification.mcp.shared.ServiceException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 统一执行“先重放、未命中再只读 ES”，确保六工具共享同一闭环。 */
@Service
public final class EvidenceToolExecutor {

    private final SnapshotReplayLookup snapshots;
    private final LiveEvidenceQuery liveEvidence;

    public EvidenceToolExecutor(SnapshotReplayLookup snapshots, LiveEvidenceQuery liveEvidence) {
        this.snapshots = snapshots;
        this.liveEvidence = liveEvidence;
    }

    /** 校验并规范化唯一参数，快照命中时绝不访问 live ES。 */
    public Object execute(EvidenceToolName toolName, Map<String, Object> input, UUID snapshotId) {
        if (snapshotId == null) {
            throw new ServiceException("EVIDENCE_SNAPSHOT_REQUIRED", "缺少证据快照标识");
        }
        Map<String, Object> arguments = validate(toolName, input);
        String argumentsHash = CanonicalToolArguments.sha256(arguments);
        Optional<Map<String, Object>> frozen = snapshots.find(snapshotId, toolName.externalName(), argumentsHash);
        return frozen.<Object>map(value -> value).orElseGet(() -> liveEvidence.query(toolName, arguments));
    }

    private static Map<String, Object> validate(EvidenceToolName toolName, Map<String, Object> input) {
        if (input == null
                || input.size() != 1
                || !input.containsKey(toolName.argumentName())
                || !(input.get(toolName.argumentName()) instanceof String value)) {
            throw new ServiceException("MCP_ARGUMENT_INVALID", "工具参数字段不符合契约");
        }
        String normalized = value.strip();
        int minimum = toolName == EvidenceToolName.RESOLVE_COMPANY ? 2 : 1;
        int maximum = toolName == EvidenceToolName.RESOLVE_COMPANY ? 200 : 100;
        if (normalized.length() < minimum || normalized.length() > maximum) {
            throw new ServiceException("MCP_ARGUMENT_INVALID", "工具参数长度不符合契约");
        }
        return Map.of(toolName.argumentName(), normalized);
    }
}
