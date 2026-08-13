package com.hsmap.factverification.mcp.shared;

/** MCP Server 的稳定业务异常；消息只包含错误码和无凭据摘要。 */
public class ServiceException extends RuntimeException {

    private final String code;

    /** 创建边界或工具调用业务异常。 */
    public ServiceException(String code, String description) {
        super(code + ": " + description);
        this.code = code;
    }

    /** 返回 MCP 客户端可稳定判断的错误码。 */
    public String getCode() {
        return code;
    }
}
