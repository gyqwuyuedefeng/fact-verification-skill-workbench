package com.hsmap.factverification.shared;

import java.util.Map;

/** OpenAPI 公开错误结构，不包含异常类、堆栈或内部连接信息。 */
public record ApiError(String code, String message, String requestId, Map<String, Object> details) {}
