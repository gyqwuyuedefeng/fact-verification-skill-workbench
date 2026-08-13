package com.hsmap.factverification.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.evaluation.gate.GateCheck;
import com.hsmap.factverification.evaluation.gate.GateResult;
import com.hsmap.factverification.evaluation.manifest.RunManifest;
import com.hsmap.factverification.evaluation.report.EvaluationReport;
import com.hsmap.factverification.evaluation.report.EvaluationReportGenerator;
import com.hsmap.factverification.evaluation.scoring.CoreMetrics;
import com.hsmap.factverification.evaluation.scoring.MetricValue;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 被测试对象：管理员可下载的评测报告生成器。
 * 测试目的：证明同条件清单、四项核心指标与发布门禁会同时进入人读 Markdown 和机器读 JSON。
 * 覆盖范围：报告字段映射和指标分子/分母，不调用模型、MCP、数据库或文件系统。
 * 前置条件：使用内存构造的最小 Run Manifest、变体汇总、核心指标和通过门禁。
 */
class EvaluationReportTest {

    /**
     * 测试场景：管理员导出一次 BASELINE 与 Candidate 的对照评测。
     * 前置条件：两个变体均有完整指标，并且 Candidate 已通过门禁。
     * 期望结果：Markdown 能直接用于人工审核，JSON 保留可程序复核的准确率分子。
     * 断言重点：同条件说明、四指标、PASS 门禁和 Candidate 准确率分子都没有在格式转换中丢失。
     */
    @Test
    void createsHumanAndMachineReadableReport() {
        RunManifest manifest = new RunManifest(
                "dataset-v1",
                "a".repeat(64),
                List.of("s1"),
                Map.of("m1", "b".repeat(64)),
                "c".repeat(64),
                Map.of(
                        "temperature", 0.0,
                        "topP", 1.0,
                        "seed", 20260812L,
                        "parallelToolCalls", false,
                        "maxTokens", 8192,
                        "enableThinking", false),
                "d".repeat(64),
                "e".repeat(64),
                "f".repeat(64),
                "0".repeat(64),
                "baseline",
                "1".repeat(64),
                120,
                3,
                "2".repeat(64));
        CoreMetrics metrics =
                new CoreMetrics(MetricValue.of(1, 1), MetricValue.of(1, 1), MetricValue.of(1, 1), MetricValue.of(0, 1));
        EvaluationResult result = new EvaluationResult(
                manifest,
                List.of(
                        new EvaluationVariantSummary("BASELINE", "BASELINE", "1".repeat(64)),
                        new EvaluationVariantSummary("SKILL", "candidate", "2".repeat(64))),
                List.of(),
                Map.of("BASELINE", metrics, "candidate", metrics));
        GateResult gate = new GateResult("PASS", List.of(new GateCheck("conditions", true, "已锁定")));

        EvaluationReport report =
                new EvaluationReportGenerator(new ObjectMapper()).generate(UUID.randomUUID(), result, gate);

        assertThat(report.markdown())
                .contains(
                        "同条件锁定",
                        "temperature=0.0",
                        "seed=20260812",
                        "maxTokens=8192",
                        "enableThinking=false",
                        "准确率",
                        "任务完成率",
                        "稳定性",
                        "人工介入率",
                        "PASS");
        assertThat(report.json()
                        .path("metrics")
                        .path("candidate")
                        .path("accuracy")
                        .path("numerator")
                        .asInt())
                .isEqualTo(1);
        assertThat(report.json().path("gate").path("status").asText()).isEqualTo("PASS");
    }
}
