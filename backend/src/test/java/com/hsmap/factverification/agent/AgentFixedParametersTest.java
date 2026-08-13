package com.hsmap.factverification.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.evaluation.dataset.GoldDataset;
import com.hsmap.factverification.evaluation.dataset.GoldDatasetLoader;
import com.hsmap.factverification.evaluation.manifest.RunManifest;
import com.hsmap.factverification.evaluation.manifest.RunManifestFactory;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 被测试对象：事实核验 Agent 与评测 Run Manifest 共用的模型采样参数。
 * 测试目的：证明 BASELINE、Stable、Candidate 不会各自回退到模型服务默认值，并能在报告中复查同一固定参数。
 * 覆盖范围：AgentScope GenerateOptions 的温度、top-p、seed、输出上限、思考模式、并行工具开关，以及 Run Manifest
 * 的参数持久化和哈希锁定。
 * 前置条件：只构造本地模型适配器与真实 v3 数据集，不发起公司模型、MCP 或数据库请求。
 */
class AgentFixedParametersTest {

    @TempDir
    Path temporaryDirectory;

    /**
     * 测试场景：创建一个通用 BASELINE Agent。
     * 前置条件：模型地址和 ID 固定，Toolkit 为空且不会真正执行 Agent。
     * 期望结果：AgentScope 收到明确的确定性采样选项，而不是 null/default。
     * 断言重点：temperature=0、topP=1、固定 seed、maxTokens=8192，显式关闭模型默认长思考，且禁止模型并行发起工具调用。
     */
    @Test
    void appliesExplicitSamplingParametersToEveryAgent() {
        WorkbenchProperties properties = new WorkbenchProperties(
                new WorkbenchProperties.DatabaseBoundary("kjjr_inx_brain", "test", false),
                Path.of("data"),
                Path.of("evals/manifest.json"),
                Path.of("skills/company-material-fact-check"),
                new WorkbenchProperties.Model(
                        "http://model.example/v1/chat/completions", "/v1/chat/completions", "qwen-company", ""),
                URI.create("http://127.0.0.1:19091/mcp"));

        try (ReActAgent agent = new FactVerificationAgentFactory(properties)
                .create(AgentVariant.baseline("a".repeat(64)), new Toolkit())) {
            assertThat(agent.getGenerateOptions().getTemperature()).isEqualTo(0.0);
            assertThat(agent.getGenerateOptions().getTopP()).isEqualTo(1.0);
            assertThat(agent.getGenerateOptions().getSeed()).isEqualTo(20260812L);
            assertThat(agent.getGenerateOptions().getParallelToolCalls()).isFalse();
            assertThat(agent.getGenerateOptions().getMaxTokens()).isEqualTo(8192);
            assertThat(agent.getGenerateOptions().getAdditionalBodyParams())
                    .containsEntry("chat_template_kwargs", Map.of("enable_thinking", false));
        }
    }

    /**
     * 测试场景：管理员创建一次 v3 同条件评测并导出 Run Manifest。
     * 前置条件：使用固定模型标识和非敏感的测试密钥占位值创建清单。
     * 期望结果：清单公开保存与 Agent 完全相同的六个非敏感生成参数，模型配置哈希覆盖这组参数。
     * 断言重点：参数 Map 与唯一运行常量一致；序列化结果不能包含 API key。
     */
    @Test
    void persistsTheSameNonSecretParametersInRunManifest() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        CanonicalJsonHasher hasher = new CanonicalJsonHasher(objectMapper);
        GoldDataset dataset = new GoldDatasetLoader(objectMapper, hasher).load(Path.of("../evals/manifest.json"));

        RunManifest manifest = new RunManifestFactory(hasher)
                .create(
                        dataset,
                        "http://model.example/v1/chat/completions",
                        "qwen-company",
                        "secret-must-not-appear",
                        FactVerificationAgentFactory.RUNTIME_IDENTITY,
                        "a".repeat(64),
                        "b".repeat(64),
                        "c".repeat(64),
                        Map.of("sample", "d".repeat(64)),
                        120);

        assertThat(manifest.modelParameters()).isEqualTo(Map.of(
                "temperature", 0.0,
                "topP", 1.0,
                "seed", 20260812L,
                "parallelToolCalls", false,
                "maxTokens", 8192,
                "enableThinking", false));
        assertThat(manifest.agentRuntimeHash())
                .isEqualTo(hasher.hash(FactVerificationAgentFactory.RUNTIME_IDENTITY));
        assertThat(objectMapper.writeValueAsString(manifest)).doesNotContain("secret-must-not-appear");
    }

    /**
     * 测试场景：评测运行一个已冻结的专用 Skill 变体。
     * 前置条件：版本父目录符合 AgentScope Skill 布局，正文和两个 references 文件共同参与整目录内容哈希。
     * 期望结果：Agent 的 system prompt 按相对路径顺序包含冻结正文和 references，不再暴露需由模型二次决策的动态加载工具。
     * 断言重点：单 Skill MVP 的每次运行必然看到同一版本的完整规则，且参考文件不会只被冻结却从未进入模型上下文。
     */
    @Test
    void injectsFrozenSkillContentWithoutDynamicLoadTool() throws Exception {
        Path skillRoot = temporaryDirectory.resolve("version-1/company-material-fact-check");
        Files.createDirectories(skillRoot.resolve("references"));
        Files.writeString(
                skillRoot.resolve("SKILL.md"),
                "---\nname: company-material-fact-check\ndescription: 专用事实核验\n---\n# 冻结专用核验规则\n必须调用企业证据工具。\n");
        Files.writeString(
                skillRoot.resolve("references/claim-normalization.md"),
                "# 主张归一化\n金额必须统一换算为元。\n");
        Files.writeString(
                skillRoot.resolve("references/evidence-rules.md"),
                "# 证据判定\n否定性风险主张必须区分无记录与不存在。\n");
        FrozenSkillLoader loader = new FrozenSkillLoader();
        String hash = loader.contentHash(skillRoot.getParent());
        WorkbenchProperties properties = new WorkbenchProperties(
                new WorkbenchProperties.DatabaseBoundary("kjjr_inx_brain", "test", false),
                Path.of("data"),
                Path.of("evals/manifest.json"),
                Path.of("skills/company-material-fact-check"),
                new WorkbenchProperties.Model(
                        "http://model.example/v1/chat/completions", "/v1/chat/completions", "qwen-company", ""),
                URI.create("http://127.0.0.1:19091/mcp"));

        try (ReActAgent agent = new FactVerificationAgentFactory(properties)
                .create(AgentVariant.skill("version-1", hash, skillRoot.getParent()), new Toolkit())) {
            assertThat(agent.getSysPrompt())
                    .contains("# 冻结专用核验规则")
                    .contains("必须调用企业证据工具")
                    .contains("--- Skill Reference: references/claim-normalization.md ---")
                    .contains("金额必须统一换算为元")
                    .contains("--- Skill Reference: references/evidence-rules.md ---")
                    .contains("否定性风险主张必须区分无记录与不存在")
                    .containsSubsequence("claim-normalization.md", "evidence-rules.md")
                    .doesNotContain("先加载 company-material-fact-check Skill");
            assertThat(agent.getToolkit().getToolNames()).doesNotContain("load_skill_through_path");
        }
    }
}
