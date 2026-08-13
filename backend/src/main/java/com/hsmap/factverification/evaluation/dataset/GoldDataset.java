package com.hsmap.factverification.evaluation.dataset;

import java.util.List;

/** 已校验并按清单顺序冻结的金标数据集。 */
public record GoldDataset(String version, String license, String contentHash, List<GoldSample> samples) {}
