package com.hsmap.factverification.evaluation.dataset;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 金标数据集声明的一次只读 MCP 证据请求。
 *
 * <p>请求只允许使用六工具固定参数，评测开始前会按规范 JSON 去重并冻结到同一证据快照；它不包含金标结论，不能向模型泄漏答案。
 */
public record GoldEvidenceRequest(String toolName, JsonNode arguments) {}
