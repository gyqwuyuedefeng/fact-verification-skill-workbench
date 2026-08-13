package com.hsmap.factverification.shared;

import java.util.regex.Pattern;

/** 将外部系统错误转换为可展示、可入报告且不含凭据的摘要。 */
public final class ErrorSanitizer {

    private static final String REDACTED = "[REDACTED]";
    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(?i)(password|passwd|token|api[-_]?key|authorization)\\s*[:=]\\s*(?:Bearer\\s+)?[^\\s,;]+");
    private static final Pattern URI_USER_INFO = Pattern.compile("(?i)(jdbc:[a-z]+://)[^/@\\s]+:[^/@\\s]+@");

    private ErrorSanitizer() {}

    /** 对空值、常见 secret key/value 和 URI 用户信息做确定性脱敏，并限制摘要长度。 */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "未提供错误摘要";
        }
        String sanitized = SECRET_PAIR.matcher(raw).replaceAll("$1=" + REDACTED);
        sanitized = URI_USER_INFO.matcher(sanitized).replaceAll("$1" + REDACTED + "@");
        sanitized = sanitized.replaceAll("[\\r\\n\\t]+", " ").trim();
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }
}
