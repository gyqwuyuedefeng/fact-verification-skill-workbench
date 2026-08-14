package com.hsmap.factverification.release;

import java.util.Set;
import java.util.UUID;

/** 发布门禁需要的同条件评测投影；版本与数量来自不可覆盖的 evaluation_run 原始列。 */
public record BootstrapEvaluation(
        UUID id, String datasetVersion, int sampleCount, String gateStatus, Set<String> variantIdentifiers) {}
