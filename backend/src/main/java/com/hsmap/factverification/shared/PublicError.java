package com.hsmap.factverification.shared;

/** 页面、API 和报告可以安全公开的稳定错误结构。 */
public record PublicError(String code, String summary) {

    /** 从内部摘要创建脱敏错误，不携带异常类名、堆栈或请求凭据。 */
    public static PublicError from(String code, String internalSummary) {
        return new PublicError(code, ErrorSanitizer.sanitize(internalSummary));
    }
}
