package com.hsmap.factverification.evaluation.api;

import com.hsmap.factverification.evaluation.EvaluationComparison;
import com.hsmap.factverification.evaluation.EvaluationCreateCommand;
import com.hsmap.factverification.evaluation.EvaluationReviewCommand;
import com.hsmap.factverification.evaluation.EvaluationRunView;
import com.hsmap.factverification.evaluation.EvaluationUseCase;
import com.hsmap.factverification.evaluation.SkillEvaluationSummary;
import com.hsmap.factverification.evaluation.report.EvaluationReport;
import com.hsmap.factverification.shared.RequestId;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 同条件评测 REST API。 */
@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

    private final EvaluationUseCase evaluations;

    public EvaluationController(EvaluationUseCase evaluations) {
        this.evaluations = evaluations;
    }

    /** 锁定清单并在后台启动真实公司模型评测。 */
    @PostMapping
    public ResponseEntity<EvaluationRunView> create(
            @RequestHeader("Idempotency-Key") String requestId, @Valid @RequestBody EvaluationCreateCommand command) {
        return ResponseEntity.accepted().body(evaluations.create(RequestId.requireValid(requestId), command));
    }

    /** 历史批次按时间倒序；versionId 只做参评清单筛选。 */
    @GetMapping
    public List<EvaluationRunView> list(@RequestParam(required = false) UUID versionId) {
        return evaluations.list(versionId);
    }

    @GetMapping("/version-summary/{versionId}")
    public SkillEvaluationSummary versionSummary(@PathVariable UUID versionId) {
        return evaluations.versionSummary(versionId);
    }

    @GetMapping("/comparison")
    public EvaluationComparison compare(@RequestParam UUID leftVersionId, @RequestParam UUID rightVersionId) {
        return evaluations.compare(leftVersionId, rightVersionId);
    }

    @GetMapping("/{evaluationId}")
    public EvaluationRunView get(@PathVariable UUID evaluationId) {
        return evaluations.get(evaluationId);
    }

    @GetMapping("/{evaluationId}/samples")
    public List<Map<String, Object>> samples(@PathVariable UUID evaluationId) {
        return evaluations.samples(evaluationId);
    }

    /** 追加人工修正，不覆盖原始模型输出和第一次评分。 */
    @PostMapping("/{evaluationId}/reviews")
    public ResponseEntity<Void> review(
            @PathVariable UUID evaluationId,
            @RequestHeader("Idempotency-Key") String requestId,
            @Valid @RequestBody EvaluationReviewCommand command) {
        evaluations.review(evaluationId, RequestId.requireValid(requestId), command);
        return ResponseEntity.status(201).build();
    }

    /** 从数据库读取同一份冻结报告并投影为 JSON 或 Markdown。 */
    @GetMapping("/{evaluationId}/report")
    public ResponseEntity<?> report(
            @PathVariable UUID evaluationId, @RequestParam(defaultValue = "json") String format) {
        EvaluationReport report = evaluations.report(evaluationId);
        if ("markdown".equals(format)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                    .body(report.markdown());
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(report.json().toString());
    }
}
