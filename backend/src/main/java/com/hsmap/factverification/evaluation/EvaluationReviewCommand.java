package com.hsmap.factverification.evaluation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** 人工修正采用追加记录，保留修正前后值和原因。 */
public record EvaluationReviewCommand(
        @NotBlank String sampleId,
        @NotBlank String variantId,
        @NotNull Map<String, Object> before,
        @NotNull Map<String, Object> after,
        @NotBlank @Size(max = 1000) String reason) {}
