package com.hsmap.factverification.release;

import java.util.Set;
import java.util.UUID;

/** 初始发布门禁需要的同条件评测投影。 */
public record BootstrapEvaluation(UUID id, String gateStatus, Set<String> variantIdentifiers) {}
