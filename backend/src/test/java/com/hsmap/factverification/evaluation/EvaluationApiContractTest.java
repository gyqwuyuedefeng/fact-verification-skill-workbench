package com.hsmap.factverification.evaluation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.evaluation.api.EvaluationController;
import com.hsmap.factverification.evaluation.report.EvaluationReport;
import com.hsmap.factverification.shared.ApiExceptionHandler;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 锁定 OpenAPI 中评测创建、详情、样本、人工修正和双格式报告路径。 */
class EvaluationApiContractTest {

    private EvaluationUseCase useCase;
    private MockMvc mvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        useCase = Mockito.mock(EvaluationUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new EvaluationController(useCase))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** 创建必须有幂等键，并把 BASELINE/Stable/Candidate 原样交给用例层。 */
    @Test
    void createsEvaluationWithIdempotencyKey() throws Exception {
        UUID id = UUID.randomUUID();
        when(useCase.create(eq("evaluation-request-001"), any()))
                .thenReturn(EvaluationRunView.pending(id, "public-tech-2024-v1", 30));

        mvc.perform(
                        post("/api/evaluations")
                                .header("Idempotency-Key", "evaluation-request-001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"datasetVersion":"public-tech-2024-v1","variantIds":["BASELINE","stable-v1","candidate-v2"]}
                                        """))
                .andExpect(status().isAccepted());

        verify(useCase).create(eq("evaluation-request-001"), any());
    }

    /** 详情、样本和人工修正路径与 OpenAPI 保持一致。 */
    @Test
    void readsDetailsSamplesAndAppendsReview() throws Exception {
        UUID id = UUID.randomUUID();
        when(useCase.get(id)).thenReturn(EvaluationRunView.pending(id, "v1", 30));
        when(useCase.samples(id)).thenReturn(List.of(Map.of("sampleId", "s1")));

        mvc.perform(get("/api/evaluations/{id}", id)).andExpect(status().isOk());
        mvc.perform(get("/api/evaluations/{id}/samples", id)).andExpect(status().isOk());
        mvc.perform(
                        post("/api/evaluations/{id}/reviews", id)
                                .header("Idempotency-Key", "review-request-001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"sampleId":"s1","variantId":"candidate-v2","before":{"status":"CONFLICT"},"after":{"status":"VERIFIED"},"reason":"人工复核原始证据"}
                                        """))
                .andExpect(status().isCreated());

        verify(useCase).review(eq(id), eq("review-request-001"), any());
    }

    /** 同一个不可变报告可按 JSON 或 Markdown 导出。 */
    @Test
    void exportsJsonAndMarkdownReport() throws Exception {
        UUID id = UUID.randomUUID();
        when(useCase.report(id))
                .thenReturn(new EvaluationReport(
                        "# report", objectMapper.createObjectNode().put("evaluationRunId", id.toString())));

        mvc.perform(get("/api/evaluations/{id}/report?format=json", id))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/evaluations/{id}/report?format=markdown", id))
                .andExpect(status().isOk())
                .andExpect(content().string("# report"));
    }
}
