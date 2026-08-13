package com.hsmap.factverification.evaluation.scoring;

/**
 * 单条金标样本的评分结果。
 *
 * <p>字段直接对应赛题四项核心指标需要的最小事实，不引入当前验收不使用的加权综合分。
 */
public record SampleScore(String sampleId, boolean accurate, boolean completed, boolean requiresHumanIntervention) {}
