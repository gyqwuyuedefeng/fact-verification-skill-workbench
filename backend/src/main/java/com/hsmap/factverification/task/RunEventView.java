package com.hsmap.factverification.task;

import java.util.Map;

/** 可重放的浏览器业务事件；id 在单运行内单调递增。 */
public record RunEventView(String id, String type, Map<String, Object> data) {
    public RunEventView {
        data = Map.copyOf(data);
    }
}
