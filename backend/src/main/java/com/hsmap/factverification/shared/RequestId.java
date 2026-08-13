package com.hsmap.factverification.shared;

import java.util.regex.Pattern;

/** 写请求幂等键的最小校验器。 */
public final class RequestId {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._:-]{8,80}");

    private RequestId() {}

    /** requestId 必须适合日志追踪和 PostgreSQL 唯一索引，不接受空白或控制字符。 */
    public static String requireValid(String requestId) {
        if (requestId == null || !ALLOWED.matcher(requestId).matches()) {
            throw new ServiceException("REQUEST_ID_INVALID", "requestId 必须为 8–80 位字母、数字或 . _ : -");
        }
        return requestId;
    }
}
