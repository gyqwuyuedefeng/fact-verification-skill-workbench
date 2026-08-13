package com.hsmap.factverification.evidence;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;

/** 一次运行独占的 MCP client 与 Toolkit，关闭时释放网络会话。 */
public final class EvidenceRunClient implements AutoCloseable {

    private final McpClientWrapper client;
    private final Toolkit toolkit;

    EvidenceRunClient(McpClientWrapper client, Toolkit toolkit) {
        this.client = client;
        this.toolkit = toolkit;
    }

    /** Agent 构造时注册了且仅注册本次快照对应的六工具。 */
    public Toolkit toolkit() {
        return toolkit;
    }

    /** 运行结束必须关闭独占 client，避免跨快照复用静态请求头。 */
    @Override
    public void close() {
        client.close();
    }
}
