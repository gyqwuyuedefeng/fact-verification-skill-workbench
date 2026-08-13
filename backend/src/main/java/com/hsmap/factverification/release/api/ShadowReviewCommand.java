package com.hsmap.factverification.release.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 一次影子人工复核，仅接受明确 PASS/FAIL。 */
public record ShadowReviewCommand(
        @NotBlank @Pattern(regexp = "PASS|FAIL") String status, @NotBlank @Size(max = 1000) String reason) {}
