package com.hsmap.factverification.evaluation.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 一条主张级金标，保留评分需要的主体、规范化主张、结论和人工证据。 */
public record GoldSample(
        String sampleId,
        String category,
        JsonNode material,
        JsonNode expectedSubject,
        JsonNode normalizedClaim,
        String expectedStatus,
        List<GoldEvidenceRequest> evidenceRequests,
        List<GoldEvidence> manualEvidence,
        JsonNode acceptableCriteria,
        List<String> edgeCases) {}
