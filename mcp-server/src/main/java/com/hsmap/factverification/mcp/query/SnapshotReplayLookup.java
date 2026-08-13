package com.hsmap.factverification.mcp.query;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 只读证据快照查询端口；MCP Server 没有写入方法。 */
@FunctionalInterface
public interface SnapshotReplayLookup {

    /** 按唯一键查询已冻结工具响应。 */
    Optional<Map<String, Object>> find(UUID snapshotId, String toolName, String argumentsHash);
}
