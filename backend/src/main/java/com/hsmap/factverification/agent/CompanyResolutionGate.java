package com.hsmap.factverification.agent;

import java.util.Comparator;
import java.util.List;

/** 用简单可复现阈值决定自动采用唯一主体或进入人工检查点。 */
public final class CompanyResolutionGate {

    private static final double MINIMUM_SCORE = 0.85;
    private static final double MINIMUM_LEAD = 0.08;

    /** 候选不足或前两名接近时暂停，绝不默认选第一条继续查财务等证据。 */
    public CompanyResolution evaluate(String query, List<CompanyCandidate> candidates) {
        List<CompanyCandidate> ranked = candidates.stream()
                .sorted(Comparator.comparingDouble(CompanyCandidate::score).reversed())
                .toList();
        if (ranked.isEmpty()) {
            return new CompanyResolution(null, query, true, ranked);
        }
        CompanyCandidate first = ranked.get(0);
        boolean ambiguous = first.score() < MINIMUM_SCORE
                || (ranked.size() > 1 && first.score() - ranked.get(1).score() < MINIMUM_LEAD);
        return ambiguous
                ? new CompanyResolution(null, query, true, ranked)
                : new CompanyResolution(first.companyId(), first.companyName(), false, ranked);
    }
}
