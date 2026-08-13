package com.hsmap.factverification.evaluation.dataset;

import java.util.List;

/** 数据集清单决定版本、文件和固定样本顺序。 */
public record DatasetManifest(
        String version, String datasetFile, int sampleCount, List<String> sampleIds, String license) {}
