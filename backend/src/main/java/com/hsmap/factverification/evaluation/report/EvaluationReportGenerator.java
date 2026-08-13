package com.hsmap.factverification.evaluation.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsmap.factverification.evaluation.EvaluationResult;
import com.hsmap.factverification.evaluation.gate.GateResult;
import com.hsmap.factverification.evaluation.scoring.CoreMetrics;
import com.hsmap.factverification.evaluation.scoring.MetricValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 生成比赛审核所需的可读报告，不写入模型密钥或材料全文。 */
public final class EvaluationReportGenerator {

    private static final Map<String, String> DEFINITIONS = Map.of(
            "accuracy", "主体、核验结论和核心证据均正确的金标主张数 / 金标主张总数",
            "completionRate", "时限内完成并产生合法结果的样本数 / 样本总数",
            "stability", "同条件三次运行主体和结论一致的抽样数 / 稳定性抽样总数",
            "humanInterventionRate", "主动请求确认或发布前必须修正的样本数 / 样本总数");

    private final ObjectMapper objectMapper;

    public EvaluationReportGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 将已经完成的原始结果投影为两个等价报告格式。 */
    public EvaluationReport generate(UUID evaluationId, EvaluationResult result, GateResult gate) {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("evaluationRunId", evaluationId.toString());
        json.set("runManifest", objectMapper.valueToTree(result.manifest()));
        json.set("variants", objectMapper.valueToTree(result.variants()));
        json.put("sampleCount", result.manifest().sampleIds().size());
        ObjectNode metricsNode = json.putObject("metrics");
        result.metrics().forEach((variant, metrics) -> metricsNode.set(variant, metricMatrix(metrics)));
        json.set("sampleResults", objectMapper.valueToTree(result.sampleResults()));
        json.set("gate", objectMapper.valueToTree(gate));
        return new EvaluationReport(markdown(result, gate), json);
    }

    private JsonNode metricMatrix(CoreMetrics metrics) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("accuracy", metric("accuracy", metrics.accuracy()));
        node.set("completionRate", metric("completionRate", metrics.completionRate()));
        node.set("stability", metric("stability", metrics.stability()));
        node.set("humanInterventionRate", metric("humanInterventionRate", metrics.humanInterventionRate()));
        return node;
    }

    private JsonNode metric(String name, MetricValue value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("definition", DEFINITIONS.get(name));
        node.put("numerator", value.numerator());
        node.put("denominator", value.denominator());
        node.put("value", value.value());
        return node;
    }

    private static String markdown(EvaluationResult result, GateResult gate) {
        StringBuilder report = new StringBuilder("# 企业材料事实核验对照评测报告\n\n");
        report.append("## 同条件锁定\n\n")
                .append("- 数据集版本：")
                .append(result.manifest().datasetVersion())
                .append('\n')
                .append("- 数据集识别值：")
                .append(result.manifest().datasetHash())
                .append('\n')
                .append("- 模型配置识别值：")
                .append(result.manifest().modelConfigHash())
                .append('\n')
                .append("- 模型采样参数：")
                .append(displayModelParameters(result.manifest().modelParameters()))
                .append('\n')
                .append("- Agent 运行时识别值：")
                .append(result.manifest().agentRuntimeHash())
                .append('\n')
                .append("- 工具契约识别值：")
                .append(result.manifest().toolContractHash())
                .append('\n')
                .append("- 证据快照识别值：")
                .append(result.manifest().evidenceSnapshotHash())
                .append('\n')
                .append("- 输出契约识别值：")
                .append(result.manifest().outputSchemaHash())
                .append("\n\n")
                .append("## 四项核心指标\n\n")
                .append("| 变体 | 准确率 | 任务完成率 | 稳定性 | 人工介入率 |\n")
                .append("|---|---:|---:|---:|---:|\n");
        result.metrics().forEach((variant, metrics) -> report.append("| ")
                .append(variant)
                .append(" | ")
                .append(display(metrics.accuracy()))
                .append(" | ")
                .append(display(metrics.completionRate()))
                .append(" | ")
                .append(display(metrics.stability()))
                .append(" | ")
                .append(display(metrics.humanInterventionRate()))
                .append(" |\n"));
        report.append("\n指标定义：\n\n");
        new LinkedHashMap<>(DEFINITIONS).forEach((name, definition) -> report.append("- ")
                .append(name)
                .append("：")
                .append(definition)
                .append('\n'));
        report.append("\n## Candidate 门禁\n\n结论：").append(gate.status()).append("\n\n");
        gate.checks().forEach(check -> report.append("- [")
                .append(check.passed() ? 'x' : ' ')
                .append("] ")
                .append(check.name())
                .append("：")
                .append(check.reason())
                .append('\n'));
        report.append("\n## 单样本结果\n\n共 ")
                .append(result.sampleResults().size())
                .append(" 条；机器可读 JSON 保留每个变体的原始输出、三次稳定性结果和评分。\n");
        return report.toString();
    }

    /**
     * 用稳定字段顺序展示与模型配置哈希一致的非敏感参数，方便审核者直接复查，而不需要先解析 JSON 报告。
     */
    private static String displayModelParameters(Map<String, Object> parameters) {
        return "temperature="
                + parameters.get("temperature")
                + ", topP="
                + parameters.get("topP")
                + ", seed="
                + parameters.get("seed")
                + ", parallelToolCalls="
                + parameters.get("parallelToolCalls")
                + ", maxTokens="
                + parameters.get("maxTokens")
                + ", enableThinking="
                + parameters.get("enableThinking");
    }

    private static String display(MetricValue value) {
        return value.numerator()
                + "/"
                + value.denominator()
                + " ("
                + String.format(java.util.Locale.ROOT, "%.2f%%", value.value() * 100)
                + ")";
    }
}
