package com.hsmap.factverification.agent;

import java.util.Map;

/** 浏览器业务事件的稳定内部表示，不暴露 AgentScope 具体事件类。 */
public record AgentBusinessEvent(String type, Map<String, Object> payload) {
    public AgentBusinessEvent {
        payload = Map.copyOf(payload);
    }
}
