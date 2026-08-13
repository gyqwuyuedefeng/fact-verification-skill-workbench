package com.hsmap.factverification.agent;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import java.util.Map;

/** 将当前页面需要的 AgentScope 事件收敛为少量可版本化业务事件。 */
public final class AgentEventMapper {

    /** 未单独映射的生命周期事件仍保留类型名，便于页面展示进度而不丢事件。 */
    public AgentBusinessEvent map(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent delta) {
            return new AgentBusinessEvent("TEXT_DELTA", Map.of("delta", delta.getDelta()));
        }
        if (event instanceof ToolCallStartEvent toolStart) {
            return new AgentBusinessEvent("TOOL_STARTED", Map.of("tool", toolStart.getToolCallName()));
        }
        if (event instanceof ToolCallEndEvent toolEnd) {
            return new AgentBusinessEvent("TOOL_ENDED", Map.of("tool", toolEnd.getToolCallName()));
        }
        if (event instanceof ToolResultTextDeltaEvent toolDelta) {
            return new AgentBusinessEvent(
                    "TOOL_RESULT_DELTA", Map.of("tool", toolDelta.getToolCallName(), "delta", toolDelta.getDelta()));
        }
        if (event instanceof AgentResultEvent result) {
            return new AgentBusinessEvent(
                    "AGENT_RESULT", Map.of("text", result.getResult().getTextContent()));
        }
        if (event instanceof AgentEndEvent) {
            return new AgentBusinessEvent("AGENT_ENDED", Map.of());
        }
        return new AgentBusinessEvent(
                "AGENT_PROGRESS", Map.of("eventType", event.getType().name()));
    }
}
