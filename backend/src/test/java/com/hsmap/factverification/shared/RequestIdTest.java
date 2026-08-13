package com.hsmap.factverification.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 写 API 使用显式 requestId 实现单人 MVP 的重复提交保护。 */
class RequestIdTest {

    /** 可追踪且适合唯一索引的 requestId 保留原值。 */
    @Test
    void acceptsCompactRequestId() {
        assertThat(RequestId.requireValid("verify-20260812-001")).isEqualTo("verify-20260812-001");
    }

    /** 空值、超长值或不可见字符不能进入数据库唯一键。 */
    @Test
    void rejectsInvalidRequestId() {
        assertThatThrownBy(() -> RequestId.requireValid("  ")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> RequestId.requireValid("x".repeat(81))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> RequestId.requireValid("bad\nrequest")).isInstanceOf(ServiceException.class);
    }
}
