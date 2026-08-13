package com.hsmap.factverification.release;

import java.util.UUID;

/** 初始 Stable 建立后的最小返回值。 */
public record InitialStableResult(long revision, UUID stableVersionId) {}
