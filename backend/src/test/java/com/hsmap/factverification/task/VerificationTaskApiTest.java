package com.hsmap.factverification.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hsmap.factverification.shared.ApiExceptionHandler;
import com.hsmap.factverification.task.api.VerificationTaskController;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 按批准的 OpenAPI 锁定任务创建、上传、运行、查询和浏览器业务事件路径。 */
class VerificationTaskApiTest {

    private static final UUID TASK_ID = UUID.fromString("76c6d424-73d6-47b2-905b-c4c1290fb6a7");
    private static final UUID RUN_ID = UUID.fromString("51f52090-25f7-4a85-bc14-650775628e8d");

    private MockMvc mockMvc;
    private FakeTaskUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new FakeTaskUseCase();
        mockMvc = MockMvcBuilders.standaloneSetup(new VerificationTaskController(useCase))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** 创建任务必须要求幂等键并返回 201。 */
    @Test
    void createsTaskWithIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/tasks").header("Idempotency-Key", "request-0001"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(TASK_ID.toString()))
                .andExpect(jsonPath("$.status").value("UPLOADED"));

        mockMvc.perform(post("/api/tasks")).andExpect(status().isBadRequest());
    }

    /** 七类材料使用 multipart 上传，授权说明不进入模型输入。 */
    @Test
    void uploadsMaterialAndStartsRun() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "company.md", "text/markdown", "# 企业\n收入1000万元".getBytes());
        mockMvc.perform(multipart("/api/tasks/{taskId}/materials", TASK_ID)
                        .file(file)
                        .param("authorizationNote", "已获内部授权")
                        .header("Idempotency-Key", "request-0002"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("READY"));

        mockMvc.perform(post("/api/tasks/{taskId}/runs", TASK_ID)
                        .header("Idempotency-Key", "request-0003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"executionMode\":\"STABLE\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.primaryRunId").value(RUN_ID.toString()));
    }

    /** 正式任务和主张查询必须只返回 PRIMARY 结果。 */
    @Test
    void getsTaskAndClaims() throws Exception {
        mockMvc.perform(get("/api/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryRunId").value(RUN_ID.toString()));
        mockMvc.perform(get("/api/tasks/{taskId}/claims", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("VERIFIED"))
                .andExpect(jsonPath("$[0].materialLocator.lineStart").value(1))
                .andExpect(jsonPath("$[0].evidence[0].dataset").value("ads_lget_company_revenue"));
        mockMvc.perform(get("/api/runs/{runId}/claims", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists());
    }

    /** 浏览器事件流是工作台业务 SSE，不改变 Agent 到 MCP 的 Streamable HTTP 硬边界。 */
    @Test
    void streamsBrowserBusinessEvents() throws Exception {
        MvcResult pending = mockMvc.perform(get("/api/runs/{runId}/events", RUN_ID))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        assertThat(completed.getResponse().getContentAsString()).contains("RUN_COMPLETED");
    }

    /** 测试替身只表达 API 合同，不模拟模型、MCP 或数据库。 */
    private static final class FakeTaskUseCase implements VerificationTaskUseCase {

        private final OffsetDateTime now = OffsetDateTime.of(2026, 8, 12, 0, 0, 0, 0, ZoneOffset.UTC);

        @Override
        public VerificationTaskView create(String requestId) {
            return task("UPLOADED", null);
        }

        @Override
        public VerificationTaskView upload(UUID taskId, String requestId, MaterialUpload material) {
            return task("READY", null);
        }

        @Override
        public VerificationTaskView start(UUID taskId, String requestId, String executionMode) {
            return task("RUNNING", RUN_ID);
        }

        @Override
        public VerificationTaskView findTask(UUID taskId) {
            return task("COMPLETED", RUN_ID);
        }

        @Override
        public List<VerificationClaimView> findPrimaryClaims(UUID taskId) {
            return claims();
        }

        @Override
        public List<VerificationClaimView> findRunClaims(UUID runId) {
            return claims();
        }

        @Override
        public List<RunEventView> replayEvents(UUID runId, String lastEventId) {
            return List.of(new RunEventView("1", "RUN_COMPLETED", Map.of("runId", RUN_ID.toString())));
        }

        private VerificationTaskView task(String status, UUID runId) {
            return new VerificationTaskView(
                    TASK_ID,
                    "company.md",
                    "a".repeat(64),
                    status.equals("UPLOADED") ? null : "b".repeat(64),
                    status,
                    false,
                    runId,
                    null,
                    null,
                    now);
        }

        private List<VerificationClaimView> claims() {
            return List.of(new VerificationClaimView(
                    UUID.randomUUID(),
                    "营业收入为1000万元",
                    Map.of("fileId", "f1", "lineStart", 1, "lineEnd", 1),
                    Map.of("metric", "营业收入", "period", "2025", "value", 1000),
                    Map.of("companyId", "C001", "companyName", "火石科技"),
                    "VERIFIED",
                    List.of(),
                    List.of(Map.of("dataset", "ads_lget_company_revenue", "recordId", "r1")),
                    "材料与企业事实一致",
                    false));
        }
    }
}
