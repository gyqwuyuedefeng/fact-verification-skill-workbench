package com.hsmap.factverification.release;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hsmap.factverification.release.api.ReleaseController;
import com.hsmap.factverification.release.api.ShadowReviewController;
import com.hsmap.factverification.shared.ApiExceptionHandler;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 按 OpenAPI 锁定注册、影子启停、晋升、回滚、查询与人工复核端点。 */
class ReleaseApiContractTest {

    private static final UUID STABLE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CANDIDATE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID EVALUATION_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID SHADOW_RUN_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");

    private ReleaseService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(ReleaseService.class);
        ReleaseStateView state = state("REGISTER", false);
        when(service.register(CANDIDATE_ID, EVALUATION_ID, "同条件评测通过")).thenReturn(state);
        when(service.startShadow("开始影子验证")).thenReturn(state("SHADOW_START", true));
        when(service.stopShadow("停止影子验证")).thenReturn(state("SHADOW_STOP", false));
        when(service.promote("人工复核通过")).thenReturn(state("PROMOTE", false));
        when(service.rollback("回滚演示")).thenReturn(state("ROLLBACK", false));
        when(service.current()).thenReturn(state);
        when(service.history()).thenReturn(List.of(state));
        mvc = MockMvcBuilders.standaloneSetup(new ReleaseController(service), new ShadowReviewController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** Candidate 注册必须同时指定不可变评测和操作原因。 */
    @Test
    void registersCandidateAndReadsReleaseState() throws Exception {
        mvc.perform(post("/api/releases/register")
                        .header("Idempotency-Key", "release-register-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                        {"candidateVersionId":"%s","evaluationRunId":"%s","reason":"同条件评测通过"}
                                        """
                                        .formatted(CANDIDATE_ID, EVALUATION_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.candidateVersionId").value(CANDIDATE_ID.toString()));

        mvc.perform(get("/api/releases/current")).andExpect(status().isOk());
        mvc.perform(get("/api/releases/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].revision").value(2));
    }

    /** 四个状态转换端点都要求幂等头和非空原因。 */
    @Test
    void changesShadowAndStableBinding() throws Exception {
        postReason("/api/releases/shadow/start", "开始影子验证");
        postReason("/api/releases/shadow/stop", "停止影子验证");
        postReason("/api/releases/promote", "人工复核通过");
        postReason("/api/releases/rollback", "回滚演示");

        verify(service).startShadow("开始影子验证");
        verify(service).stopShadow("停止影子验证");
        verify(service).promote("人工复核通过");
        verify(service).rollback("回滚演示");
    }

    /** 只有 SHADOW 运行可追加一次 PASS/FAIL 人工复核。 */
    @Test
    void reviewsCompletedShadowRun() throws Exception {
        mvc.perform(post("/api/runs/{runId}/review", SHADOW_RUN_ID)
                        .header("Idempotency-Key", "shadow-review-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PASS\",\"reason\":\"正式结果与影子结果一致\"}"))
                .andExpect(status().isCreated());

        verify(service).reviewShadow(eq(SHADOW_RUN_ID), eq("PASS"), eq("正式结果与影子结果一致"));
    }

    private void postReason(String path, String reason) throws Exception {
        mvc.perform(post(path)
                        .header("Idempotency-Key", "release-operation-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk());
    }

    private static ReleaseStateView state(String action, boolean shadowEnabled) {
        return new ReleaseStateView(
                2,
                STABLE_ID,
                CANDIDATE_ID,
                null,
                shadowEnabled,
                action,
                "test",
                OffsetDateTime.of(2026, 8, 12, 0, 0, 0, 0, ZoneOffset.UTC));
    }
}
