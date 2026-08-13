package com.hsmap.factverification.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hsmap.factverification.shared.ApiExceptionHandler;
import com.hsmap.factverification.task.api.VerificationTaskController;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 锁定普通对话核验的最小合同。
 *
 * <p>普通入口只允许 BASELINE/当前 Stable，不接收 Candidate 或影子参数；文字输入和附件共用同一材料固定路径。
 */
class ChatVerificationApiTest {

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

    /** 纯文字也能固定为 TEXT 材料，不要求前端伪造文件。 */
    @Test
    void acceptsTextOnlyMaterial() throws Exception {
        mockMvc.perform(multipart("/api/tasks/{taskId}/materials", TASK_ID)
                        .param("message", "某模拟企业2025年营业收入为1.2亿元。")
                        .header("Idempotency-Key", "request-text-001"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.inputType").value("TEXT"))
                .andExpect(jsonPath("$.messagePresent").value(true));

        assertThat(useCase.lastUpload.message()).contains("营业收入");
        assertThat(useCase.lastUpload.content()).isNull();
    }

    /** 附件和文字可以组合，二者均为空时必须在 API 边界拒绝。 */
    @Test
    void acceptsCombinedInputAndRejectsEmptyInput() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "company.md", "text/markdown", "# 模拟材料".getBytes());
        mockMvc.perform(multipart("/api/tasks/{taskId}/materials", TASK_ID)
                        .file(file)
                        .param("message", "重点核验收入与专利。")
                        .header("Idempotency-Key", "request-combined-001"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.inputType").value("COMBINED"));

        mockMvc.perform(multipart("/api/tasks/{taskId}/materials", TASK_ID)
                        .header("Idempotency-Key", "request-empty-001"))
                .andExpect(status().isBadRequest());
    }

    /** 普通运行只接受 BASELINE 或 STABLE，合同中不再出现 includeShadow。 */
    @Test
    void startsOnlyBaselineOrStable() throws Exception {
        mockMvc.perform(post("/api/tasks/{taskId}/runs", TASK_ID)
                        .header("Idempotency-Key", "request-run-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"executionMode\":\"BASELINE\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionMode").value("BASELINE"))
                .andExpect(jsonPath("$.primaryRunId").value(RUN_ID.toString()));

        assertThat(useCase.lastExecutionMode).isEqualTo("BASELINE");

        mockMvc.perform(post("/api/tasks/{taskId}/runs", TASK_ID)
                        .header("Idempotency-Key", "request-run-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"executionMode\":\"CANDIDATE\"}"))
                .andExpect(status().isBadRequest());
    }

    /** 测试替身只验证 HTTP 到用例边界的参数，不模拟 Agent。 */
    private static final class FakeTaskUseCase implements VerificationTaskUseCase {

        private MaterialUpload lastUpload;
        private String lastExecutionMode;

        @Override
        public VerificationTaskView create(String requestId) {
            return task(null, null);
        }

        @Override
        public VerificationTaskView upload(UUID taskId, String requestId, MaterialUpload material) {
            lastUpload = material;
            String type =
                    material.content() == null ? "TEXT" : material.message().isBlank() ? "FILE" : "COMBINED";
            return task(type, null);
        }

        @Override
        public VerificationTaskView start(UUID taskId, String requestId, String executionMode) {
            lastExecutionMode = executionMode;
            return task("TEXT", executionMode);
        }

        @Override
        public VerificationTaskView findTask(UUID taskId) {
            return task("TEXT", "BASELINE");
        }

        @Override
        public List<VerificationClaimView> findPrimaryClaims(UUID taskId) {
            return List.of();
        }

        @Override
        public List<VerificationClaimView> findRunClaims(UUID runId) {
            return List.of();
        }

        @Override
        public List<RunEventView> replayEvents(UUID runId, String lastEventId) {
            return List.of();
        }

        private VerificationTaskView task(String inputType, String executionMode) {
            return new VerificationTaskView(
                    TASK_ID,
                    inputType,
                    inputType != null,
                    executionMode,
                    inputType == null ? null : inputType.equals("TEXT") ? "message.txt" : "company.md",
                    inputType == null ? null : "a".repeat(64),
                    inputType == null ? null : "b".repeat(64),
                    inputType == null ? "UPLOADED" : executionMode == null ? "READY" : "RUNNING",
                    executionMode == null ? null : RUN_ID,
                    null,
                    OffsetDateTime.of(2026, 8, 12, 0, 0, 0, 0, ZoneOffset.UTC));
        }
    }
}
