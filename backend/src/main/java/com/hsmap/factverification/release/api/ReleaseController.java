package com.hsmap.factverification.release.api;

import com.hsmap.factverification.release.ReleaseService;
import com.hsmap.factverification.release.ReleaseStateView;
import com.hsmap.factverification.shared.RequestId;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 单 Skill 家族的最小发布 API，不暴露通用审批流或多租户路由。 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseService releases;

    public ReleaseController(ReleaseService releases) {
        this.releases = releases;
    }

    /** 评测门禁通过后注册 Candidate；首次注册可建立初始 Stable。 */
    @PostMapping("/register")
    public ResponseEntity<ReleaseStateView> register(
            @RequestHeader("Idempotency-Key") String requestId, @Valid @RequestBody ReleaseCommand command) {
        RequestId.requireValid(requestId);
        return ResponseEntity.status(201)
                .body(releases.register(command.candidateVersionId(), command.evaluationRunId(), command.reason()));
    }

    /** 开启后，仅显式勾选影子的真实任务会后台运行 Candidate。 */
    @PostMapping("/shadow/start")
    public ReleaseStateView startShadow(
            @RequestHeader("Idempotency-Key") String requestId, @Valid @RequestBody ReasonCommand command) {
        RequestId.requireValid(requestId);
        return releases.startShadow(command.reason());
    }

    /** 停止创建新 SHADOW，不终止已经钉死版本并开始的运行。 */
    @PostMapping("/shadow/stop")
    public ReleaseStateView stopShadow(
            @RequestHeader("Idempotency-Key") String requestId, @Valid @RequestBody ReasonCommand command) {
        RequestId.requireValid(requestId);
        return releases.stopShadow(command.reason());
    }

    /** 离线门禁和至少一次影子 PASS 均满足后，将 Candidate 晋升为 Stable。 */
    @PostMapping("/promote")
    public ReleaseStateView promote(
            @RequestHeader("Idempotency-Key") String requestId, @Valid @RequestBody ReasonCommand command) {
        RequestId.requireValid(requestId);
        return releases.promote(command.reason());
    }

    /** 恢复上一 Stable，仅影响此操作之后创建的新任务运行。 */
    @PostMapping("/rollback")
    public ReleaseStateView rollback(
            @RequestHeader("Idempotency-Key") String requestId, @Valid @RequestBody ReasonCommand command) {
        RequestId.requireValid(requestId);
        return releases.rollback(command.reason());
    }

    @GetMapping("/current")
    public ReleaseStateView current() {
        return releases.current();
    }

    @GetMapping("/history")
    public List<ReleaseStateView> history() {
        return releases.history();
    }
}
