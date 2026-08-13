package com.hsmap.factverification.evaluation;

/** 报告中冻结的参评变体标识，不包含 Skill 全文。 */
public record EvaluationVariantSummary(String type, String identifier, String contentHash) {}
