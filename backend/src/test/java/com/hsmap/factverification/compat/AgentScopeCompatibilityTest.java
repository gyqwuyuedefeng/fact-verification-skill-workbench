package com.hsmap.factverification.compat;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 锁定 AgentScope Java 2.0.1 在本项目真正使用的最小 API 面。
 *
 * <p>测试刻意编译具体类型和 tagged API，防止依赖被动态版本或在线文档中尚未发布的写法悄悄替换。它不发起模型或 MCP 网络调用。
 */
class AgentScopeCompatibilityTest {

    /** FireLM 的完整 URL 必须无配置迁移即可复用。 */
    @Test
    void splitsExistingFireLmChatCompletionsUrl() {
        AgentScopeRuntimeCompatibility.ModelEndpoint endpoint = AgentScopeRuntimeCompatibility.resolveModelEndpoint(
                "https://model.example/internal/v1/chat/completions", "/v1/chat/completions");

        assertThat(endpoint.baseUrl()).isEqualTo("https://model.example/internal");
        assertThat(endpoint.endpointPath()).isEqualTo("/v1/chat/completions");
    }

    @TempDir
    Path temporaryDirectory;

    /** 只读 Skill repository 的根必须是 Skill 子目录的父目录。 */
    @Test
    void loadsSkillFromReadOnlyParentDirectory() throws Exception {
        Path skillDirectory = temporaryDirectory.resolve("company-material-fact-check");
        Files.createDirectories(skillDirectory);
        Files.writeString(
                skillDirectory.resolve("SKILL.md"),
                "---\nname: company-material-fact-check\ndescription: test\n---\n# Test\n");

        FileSystemSkillRepository repository =
                AgentScopeRuntimeCompatibility.readOnlySkillRepository(temporaryDirectory);

        assertThat(repository.getAllSkillNames()).containsExactly("company-material-fact-check");
    }

    /** 公司千问适配只依赖 OpenAI-compatible builder，不在兼容测试中发送真实请求。 */
    @Test
    void buildsOpenAiCompatibleModelWithPinnedApi() {
        OpenAIChatModel model = AgentScopeRuntimeCompatibility.openAiCompatibleModel(
                "http://127.0.0.1:19000", "/v1/chat/completions", "company-qwen", "test-key");

        assertThat(model.getModelName()).isEqualTo("company-qwen");
    }

    /** MCP 客户端必须从 2.0.1 的 Streamable HTTP builder 创建并静态钉死证据快照。 */
    @Test
    void createsStreamableHttpMcpBuilderWithSnapshotHeader() {
        McpClientBuilder builder =
                AgentScopeRuntimeCompatibility.streamableMcpBuilder("http://127.0.0.1:18081/mcp", "snapshot-001");

        assertThat(builder).isNotNull();
    }

    /**
     * 测试场景：固定的企业证据 Agent 调用服务端白名单只读工具。
     * 前置条件：Agent 禁用元工具、任务列表和代码执行，Toolkit 只注册六个 MCP 工具及 Skill 只读装载工具。
     * 期望结果：工具调用无需页面人工确认，避免 ReActAgent 在第一次取证时 REQUEST_STOP。
     * 断言重点：权限上下文使用 BYPASS；其安全边界由受限 Toolkit 而不是运行时弹窗提供。
     */
    @Test
    void preapprovesToolsInsideRestrictedEvidenceAgent() {
        assertThat(AgentScopeRuntimeCompatibility.restrictedAgentPermissionContext().getMode())
                .isEqualTo(PermissionMode.BYPASS);
    }

    /** 页面事件必须来自正式 streamEvents API，不能退回已废弃的旧 stream 方法。 */
    @Test
    void exposesFineGrainedAgentEventStream() throws Exception {
        assertThat(ReActAgent.class.getMethod("streamEvents", String.class).getReturnType())
                .isEqualTo(Flux.class);
    }
}
