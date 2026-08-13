package com.hsmap.factverification.mcp.query;

import com.hsmap.factverification.mcp.contract.EvidenceToolName;
import java.util.Map;

/** 未命中快照时唯一允许访问 live 企业证据的只读端口。 */
@FunctionalInterface
public interface LiveEvidenceQuery {

    /** 返回契约定义的统一 evidence envelope。 */
    Object query(EvidenceToolName toolName, Map<String, Object> arguments);
}
