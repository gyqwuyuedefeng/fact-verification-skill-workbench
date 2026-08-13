package com.hsmap.factverification.agent;

/** 主体搜索候选的最小字段。 */
public record CompanyCandidate(String companyId, String companyName, double score) {}
