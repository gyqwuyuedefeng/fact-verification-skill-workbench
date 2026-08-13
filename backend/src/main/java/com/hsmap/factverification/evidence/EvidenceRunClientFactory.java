package com.hsmap.factverification.evidence;

import com.hsmap.factverification.compat.AgentScopeRuntimeCompatibility;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.shared.ServiceException;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 为每次核验或评测创建独立 Streamable HTTP MCP client。 */
@Component
public final class EvidenceRunClientFactory {

    private final WorkbenchProperties properties;
    private final EvidenceSnapshotRecorder recorder;

    public EvidenceRunClientFactory(WorkbenchProperties properties, EvidenceSnapshotRecorder recorder) {
        this.properties = properties;
        this.recorder = recorder;
    }

    /** 静态钉死 snapshot header，注册工具完成后才把 client 交给 Agent。 */
    public EvidenceRunClient open(UUID snapshotId, String ownerType, UUID ownerId) {
        McpClientWrapper client = AgentScopeRuntimeCompatibility.streamableMcpBuilder(
                        properties.mcpEndpoint().toString(), snapshotId.toString())
                .buildAsync()
                .block(Duration.ofSeconds(70));
        if (client == null) {
            throw new ServiceException("MCP_CLIENT_INITIALIZATION_FAILED", "企业证据工具连接失败");
        }
        Toolkit toolkit = new Toolkit();
        try {
            toolkit.registerMcpClient(client).block(Duration.ofSeconds(70));
            recorder.attach(toolkit, snapshotId, ownerType, ownerId);
            return new EvidenceRunClient(client, toolkit);
        } catch (RuntimeException exception) {
            client.close();
            throw new ServiceException("MCP_CLIENT_INITIALIZATION_FAILED", "企业证据工具连接失败");
        }
    }
}
