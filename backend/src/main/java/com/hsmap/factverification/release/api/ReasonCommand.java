package com.hsmap.factverification.release.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 所有发布状态转换都必须留下可审核原因。 */
public record ReasonCommand(@NotBlank @Size(max = 1000) String reason) {}
