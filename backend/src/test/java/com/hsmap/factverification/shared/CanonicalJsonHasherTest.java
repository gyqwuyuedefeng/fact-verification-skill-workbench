package com.hsmap.factverification.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证内容识别值不会受 JSON 字段顺序或 Map 实现影响。 */
class CanonicalJsonHasherTest {

    private final CanonicalJsonHasher hasher = new CanonicalJsonHasher(new ObjectMapper());

    /** 同一业务输入即使字段顺序不同，也必须得到同一个 SHA-256。 */
    @Test
    void hashesSemanticallyEquivalentObjectsIdentically() {
        String first = hasher.hash(Map.of("company", "火石创造", "year", 2025));
        String second = hasher.hash(Map.of("year", 2025, "company", "火石创造"));

        assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");
    }

    /** 内容变化必须改变识别值，避免评测条件被误判为相同。 */
    @Test
    void changesHashWhenContentChanges() {
        assertThat(hasher.hash(Map.of("value", 1))).isNotEqualTo(hasher.hash(Map.of("value", 2)));
    }
}
