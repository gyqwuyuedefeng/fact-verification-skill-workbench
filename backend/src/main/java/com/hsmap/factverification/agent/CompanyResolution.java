package com.hsmap.factverification.agent;

import java.util.List;

/** 主体门禁结果；需要人工时 companyId 为空且保留候选列表供页面选择。 */
public record CompanyResolution(
        String companyId, String companyName, boolean requiresHumanConfirmation, List<CompanyCandidate> candidates) {
    public CompanyResolution {
        candidates = List.copyOf(candidates);
    }
}
