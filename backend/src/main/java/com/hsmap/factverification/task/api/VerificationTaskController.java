package com.hsmap.factverification.task.api;

import com.hsmap.factverification.shared.RequestId;
import com.hsmap.factverification.task.MaterialUpload;
import com.hsmap.factverification.task.RunEventView;
import com.hsmap.factverification.task.VerificationClaimView;
import com.hsmap.factverification.task.VerificationTaskUseCase;
import com.hsmap.factverification.task.VerificationTaskView;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 企业材料核验任务 API；浏览器事件流与 MCP transport 完全独立。 */
@RestController
@RequestMapping("/api")
public class VerificationTaskController {

    private final VerificationTaskUseCase tasks;

    public VerificationTaskController(VerificationTaskUseCase tasks) {
        this.tasks = tasks;
    }

    /** 创建可接收一份材料的任务槽。 */
    @PostMapping("/tasks")
    public ResponseEntity<VerificationTaskView> createTask(@RequestHeader("Idempotency-Key") String requestId) {
        return ResponseEntity.status(201).body(tasks.create(RequestId.requireValid(requestId)));
    }

    /** 保存并同步完成确定性解析；202 表示后续 Agent 尚未运行。 */
    @PostMapping(path = "/tasks/{taskId}/materials", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VerificationTaskView> uploadMaterial(
            @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") String requestId,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "message", required = false) String message,
            @RequestParam(value = "authorizationNote", required = false) String authorizationNote) {
        if ((file == null || file.isEmpty()) && (message == null || message.isBlank())) {
            throw new com.hsmap.factverification.shared.ServiceException("MATERIAL_REQUIRED", "请输入文字或上传文件");
        }
        MaterialUpload material = new MaterialUpload(
                file == null ? null : file.getOriginalFilename(),
                file == null ? "text/plain" : file.getContentType(),
                file == null ? 0 : file.getSize(),
                authorizationNote,
                message,
                file == null ? null : file::getInputStream);
        return ResponseEntity.accepted().body(tasks.upload(taskId, RequestId.requireValid(requestId), material));
    }

    /** 普通入口只允许 BASELINE 或当前 Stable；Candidate/影子由管理发布状态控制。 */
    @PostMapping("/tasks/{taskId}/runs")
    public ResponseEntity<VerificationTaskView> startRun(
            @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") String requestId,
            @Valid @RequestBody StartRunRequest body) {
        return ResponseEntity.accepted()
                .body(tasks.start(taskId, RequestId.requireValid(requestId), body.executionMode()));
    }

    @GetMapping("/tasks/{taskId}")
    public VerificationTaskView getTask(@PathVariable UUID taskId) {
        return tasks.findTask(taskId);
    }

    /** 正式页面只读取 PRIMARY 主张。 */
    @GetMapping("/tasks/{taskId}/claims")
    public List<VerificationClaimView> getPrimaryClaims(@PathVariable UUID taskId) {
        return tasks.findPrimaryClaims(taskId);
    }

    /** 管理页面可显式按 runId 读取 PRIMARY 或 SHADOW。 */
    @GetMapping("/runs/{runId}/claims")
    public List<VerificationClaimView> getRunClaims(@PathVariable UUID runId) {
        return tasks.findRunClaims(runId);
    }

    /**
     * 返回本运行已保存的业务事件后完成连接。
     *
     * <p>单人 MVP 不保留长连接事件总线；页面轮询任务状态并可用 Last-Event-ID 重取历史事件。
     */
    @GetMapping(path = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(
            @PathVariable UUID runId, @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = new SseEmitter(5_000L);
        Thread sender = new Thread(
                () -> {
                    try {
                        for (RunEventView event : tasks.replayEvents(runId, lastEventId)) {
                            emitter.send(SseEmitter.event()
                                    .id(event.id())
                                    .name(event.type())
                                    .data(event.data()));
                        }
                        emitter.complete();
                    } catch (IOException exception) {
                        emitter.completeWithError(exception);
                    }
                },
                "run-event-replay-" + runId);
        sender.setDaemon(true);
        sender.start();
        return emitter;
    }

    /** 普通用户可见运行方式白名单，不能传 Candidate 或具体版本 ID。 */
    public record StartRunRequest(
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp = "BASELINE|STABLE") String executionMode) {}
}
