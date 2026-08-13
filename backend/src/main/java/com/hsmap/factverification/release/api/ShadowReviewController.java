package com.hsmap.factverification.release.api;

import com.hsmap.factverification.release.ReleaseService;
import com.hsmap.factverification.shared.RequestId;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 影子运行人工复核入口；与正式任务查询 API 分离，避免误改 PRIMARY。 */
@RestController
@RequestMapping("/api/runs")
public class ShadowReviewController {

    private final ReleaseService releases;

    public ShadowReviewController(ReleaseService releases) {
        this.releases = releases;
    }

    @PostMapping("/{runId}/review")
    public ResponseEntity<Void> review(
            @PathVariable UUID runId,
            @RequestHeader("Idempotency-Key") String requestId,
            @Valid @RequestBody ShadowReviewCommand command) {
        RequestId.requireValid(requestId);
        releases.reviewShadow(runId, command.status(), command.reason());
        return ResponseEntity.status(201).build();
    }
}
