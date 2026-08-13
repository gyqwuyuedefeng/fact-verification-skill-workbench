package com.hsmap.factverification.evaluation.report;

import com.fasterxml.jackson.databind.JsonNode;

/** 一次完成后不可覆盖的 Markdown 和 JSON 双格式报告。 */
public record EvaluationReport(String markdown, JsonNode json) {}
