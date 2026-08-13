package com.hsmap.factverification.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.agent.AgentVariant;
import com.hsmap.factverification.evaluation.dataset.GoldSample;
import com.hsmap.factverification.evaluation.manifest.RunManifest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证正式评测发送给公司模型的运行元数据与统一结果 schema 完全同形。 */
class AgentEvaluationExecutorTest {

    /**
     * 测试场景：评测样本使用 BASELINE 生成公司模型提示。
     *
     * <p>前置条件：统一输出契约要求顶层 `variant` 是包含 type、identifier、contentHash 的对象，而不是三个扁平字段。
     * 期望结果：评测提示与普通对话使用同一嵌套结构，模型能够逐字复制为合法结果。断言重点：不得再出现造成正式评测批量 schema
     * 失败的 variantType、variantIdentifier 或 variantContentHash。
     */
    @Test
    void usesNestedVariantMetadataRequiredByTheOutputSchema() {
        ObjectMapper objectMapper = new ObjectMapper();
        UUID evaluationId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        String variantHash = "a".repeat(64);
        AgentVariant variant = AgentVariant.baseline(variantHash);
        var material = objectMapper.createObjectNode().put("text", "测试主张");
        material.set("locator", objectMapper.createObjectNode().put("type", "LINE").put("value", "L1"));
        GoldSample sample = new GoldSample(
                "sample-1",
                "BASIC",
                material,
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                "INSUFFICIENT",
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                List.of());
        RunManifest manifest = new RunManifest(
                "dataset-v1",
                "b".repeat(64),
                List.of("sample-1"),
                Map.of("sample-1", "c".repeat(64)),
                "d".repeat(64),
                Map.of(),
                "e".repeat(64),
                "f".repeat(64),
                "1".repeat(64),
                "2".repeat(64),
                "baseline",
                variantHash,
                120,
                3,
                "3".repeat(64));
        EvaluationExecutionRequest request =
                new EvaluationExecutionRequest(evaluationId, snapshotId, runId, sample, variant, manifest, 1);

        String prompt = new AgentEvaluationExecutor(null, objectMapper).buildPrompt(request);

        assertThat(prompt)
                .contains("\"variant\":{\"type\":\"BASELINE\",\"identifier\":\"BASELINE\",\"contentHash\":\"" + variantHash + "\"}")
                .doesNotContain("variantType", "variantIdentifier", "variantContentHash");
    }

    /**
     * 测试场景：金标清单使用便于人工阅读的 LINE/L1 定位，模型结果 schema 使用解析器统一 locator。
     * 前置条件：材料文本只有一条主张，原始金标 locator 为 {type: LINE, value: L1}。
     * 期望结果：发送给模型的材料快照确定性投影为 fileId、lineStart、lineEnd，模型可以原样复制并通过 schema。
     * 断言重点：不得把仅供金标描述的 type/value 结构直接暴露给模型，造成“按提示复制即校验失败”的矛盾。
     */
    @Test
    void projectsHumanReadableGoldLocatorToTheResultSchemaLocator() {
        ObjectMapper objectMapper = new ObjectMapper();
        var material = objectMapper.createObjectNode().put("text", "测试主张");
        material.set("locator", objectMapper.createObjectNode().put("type", "LINE").put("value", "L1"));
        GoldSample sample = new GoldSample(
                "sample-1",
                "BASIC",
                material,
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                "INSUFFICIENT",
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                List.of());
        AgentVariant variant = AgentVariant.baseline("a".repeat(64));
        RunManifest manifest = new RunManifest(
                "dataset-v1",
                "b".repeat(64),
                List.of("sample-1"),
                Map.of("sample-1", "c".repeat(64)),
                "d".repeat(64),
                Map.of(),
                "e".repeat(64),
                "f".repeat(64),
                "1".repeat(64),
                "2".repeat(64),
                "baseline",
                variant.contentHash(),
                120,
                3,
                "3".repeat(64));
        EvaluationExecutionRequest request = new EvaluationExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), sample, variant, manifest, 1);

        String prompt = new AgentEvaluationExecutor(null, objectMapper).buildPrompt(request);

        assertThat(prompt)
                .contains("\"locator\":{\"fileId\":\"sample-1\",\"lineStart\":1,\"lineEnd\":1}")
                .doesNotContain("\"type\":\"LINE\"", "\"value\":\"L1\"");
    }
}
