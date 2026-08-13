package com.hsmap.factverification.evaluation.dataset;

/** 人工核对过的外部证据引用；只保存定位信息，不复制业务数据全文。 */
public record GoldEvidence(
        String toolName, String dataset, String recordId, String retrievedAt, String sourceUrl, String sourceLocator) {}
