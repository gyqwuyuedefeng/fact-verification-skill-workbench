package com.hsmap.factverification.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 防止数据库、模型和 ES 凭据经错误摘要进入页面或比赛报告。 */
class ErrorSanitizerTest {

    /** 常见 key/value、Bearer token 和 JDBC 用户信息必须统一脱敏。 */
    @Test
    void removesCredentialsFromExternalErrorSummary() {
        String raw =
                "password=secret123 api_key=qwen-secret Authorization: Bearer abc.def jdbc:postgresql://user:pass@db/test";

        String sanitized = ErrorSanitizer.sanitize(raw);

        assertThat(sanitized)
                .doesNotContain("secret123", "qwen-secret", "abc.def", "user:pass")
                .contains("[REDACTED]");
    }

    /** 页面错误只暴露稳定 code 和脱敏摘要，不带内部异常对象。 */
    @Test
    void createsStablePublicError() {
        PublicError error = PublicError.from("EVIDENCE_UNAVAILABLE", "token=internal-token timeout");

        assertThat(error.code()).isEqualTo("EVIDENCE_UNAVAILABLE");
        assertThat(error.summary()).doesNotContain("internal-token");
    }
}
