package com.hsmap.factverification.evaluation;

import com.hsmap.factverification.evaluation.report.EvaluationReport;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 评测页面使用的最小业务边界。 */
public interface EvaluationUseCase {

    EvaluationRunView create(String requestId, EvaluationCreateCommand command);

    EvaluationRunView get(UUID evaluationId);

    List<EvaluationRunView> list(UUID versionId);

    SkillEvaluationSummary versionSummary(UUID versionId);

    EvaluationComparison compare(UUID leftVersionId, UUID rightVersionId);

    List<Map<String, Object>> samples(UUID evaluationId);

    void review(UUID evaluationId, String requestId, EvaluationReviewCommand command);

    EvaluationReport report(UUID evaluationId);
}
