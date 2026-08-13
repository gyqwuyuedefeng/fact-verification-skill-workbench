package com.hsmap.factverification.release.api;

import com.hsmap.factverification.release.ShadowHistory;
import com.hsmap.factverification.release.ShadowHistoryService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理控制台的影子历史只读入口。 */
@RestController
@RequestMapping("/api/shadow-runs")
public class ShadowHistoryController {

    private final ShadowHistoryService history;

    public ShadowHistoryController(ShadowHistoryService history) {
        this.history = history;
    }

    @GetMapping
    public ShadowHistory list(
            @RequestParam(required = false) String reviewStatus, @RequestParam(required = false) UUID versionId) {
        return history.list(reviewStatus, versionId);
    }
}
