package com.hsmap.factverification.evaluation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 创建评测时只选择数据集版本和参评变体。 */
public record EvaluationCreateCommand(
        @NotBlank String datasetVersion, @Size(min = 2) List<@NotBlank String> variantIds) {}
