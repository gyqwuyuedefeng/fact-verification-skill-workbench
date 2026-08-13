package com.hsmap.factverification.release;

import com.hsmap.factverification.run.persistence.VerificationRunRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 基于既有任务/运行/主张表生成影子观察汇总，不新建报表事实表。 */
@Service
public class ShadowHistoryService {

    private final VerificationRunRepository runs;

    public ShadowHistoryService(VerificationRunRepository runs) {
        this.runs = runs;
    }

    /** 可选按复核状态或 Candidate 版本筛选，其他字段由管理页即时筛选。 */
    public ShadowHistory list(String reviewStatus, UUID versionId) {
        List<VerificationRunRepository.ShadowRunRow> items = runs.listShadowRuns().stream()
                .filter(item -> reviewStatus == null || reviewStatus.equals(item.reviewStatus()))
                .filter(item -> versionId == null || versionId.equals(item.candidateVersionId()))
                .toList();
        int completed = (int) items.stream()
                .filter(item -> "COMPLETED".equals(item.shadowStatus()))
                .count();
        int pass = (int) items.stream()
                .filter(item -> "PASS".equals(item.reviewStatus()))
                .count();
        int fail = (int) items.stream()
                .filter(item -> "FAIL".equals(item.reviewStatus()))
                .count();
        int differentClaims = items.stream()
                .mapToInt(VerificationRunRepository.ShadowRunRow::differenceCount)
                .sum();
        return new ShadowHistory(
                items,
                Map.of(
                        "total", items.size(),
                        "completed", completed,
                        "pass", pass,
                        "fail", fail,
                        "differentClaims", differentClaims),
                false);
    }
}
