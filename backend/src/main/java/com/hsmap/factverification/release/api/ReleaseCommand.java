package com.hsmap.factverification.release.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** 注册 Candidate 所需的最小不可变关联。 */
public record ReleaseCommand(
        @NotNull UUID candidateVersionId, @NotNull UUID evaluationRunId, @NotBlank @Size(max = 1000) String reason) {}
