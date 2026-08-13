package com.hsmap.factverification.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 页面展示的一条核验主张，保留 locator、标准化值和外部证据。 */
public record VerificationClaimView(
        UUID id,
        String claimText,
        Map<String, Object> materialLocator,
        Map<String, Object> normalizedClaim,
        Map<String, Object> subject,
        String status,
        List<String> riskFlags,
        List<Map<String, Object>> evidence,
        String explanation,
        boolean requiresHumanIntervention) {
    public VerificationClaimView {
        materialLocator = Collections.unmodifiableMap(new LinkedHashMap<>(materialLocator));
        normalizedClaim = Collections.unmodifiableMap(new LinkedHashMap<>(normalizedClaim));
        subject = subject == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(subject));
        riskFlags = List.copyOf(riskFlags);
        evidence = evidence.stream()
                .map(item -> Collections.unmodifiableMap(new LinkedHashMap<>(item)))
                .toList();
    }
}
