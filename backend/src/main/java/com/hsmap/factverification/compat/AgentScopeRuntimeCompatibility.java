package com.hsmap.factverification.compat;

import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 集中封装本项目经编译验证的 AgentScope Java 2.0.1 API。
 *
 * <p>该类不是通用框架适配层，只隔离当前 MVP 的三个易漂移点：公司 OpenAI-compatible 模型、只读文件 Skill 仓库和原生 Streamable
 * HTTP MCP 客户端。后续业务代码统一从这里创建对象，避免在线文档与锁定 artifact 的 API 差异散落到各模块。
 */
public final class AgentScopeRuntimeCompatibility {

    private static final String MCP_PROTOCOL_VERSION = "2025-03-26";

    private AgentScopeRuntimeCompatibility() {}

    /**
     * 创建公司千问模型适配器。
     *
     * @param baseUrl OpenAI-compatible 服务根地址
     * @param endpointPath chat-completions 路径
     * @param modelName 公司配置的模型 ID
     * @param apiKey 可选 Bearer key；无鉴权的测试环境可传空串
     * @return 已启用流式响应的 AgentScope 模型
     */
    public static OpenAIChatModel openAiCompatibleModel(
            String baseUrl, String endpointPath, String modelName, String apiKey) {
        ModelEndpoint endpoint = resolveModelEndpoint(baseUrl, endpointPath);
        return OpenAIChatModel.builder()
                .apiKey(apiKey == null ? "" : apiKey)
                .baseUrl(endpoint.baseUrl())
                .endpointPath(endpoint.endpointPath())
                .modelName(modelName)
                .stream(true)
                .build();
    }

    /**
     * FireLM 现有配置保存的是完整 chat-completions URL；新项目也兼容“服务根 + endpoint”写法。
     *
     * <p>这里只做两个已知 OpenAI-compatible 后缀的确定性拆分，不引入通用 URL 猜测逻辑。
     */
    public static ModelEndpoint resolveModelEndpoint(String configuredUrl, String endpointPath) {
        String url = configuredUrl == null ? "" : configuredUrl.strip().replaceAll("/+$", "");
        for (String suffix : new String[] {"/v1/chat/completions", "/chat/completions"}) {
            if (url.endsWith(suffix) && url.length() > suffix.length()) {
                return new ModelEndpoint(url.substring(0, url.length() - suffix.length()), suffix);
            }
        }
        String path = endpointPath == null || endpointPath.isBlank() ? "/v1/chat/completions" : endpointPath.strip();
        return new ModelEndpoint(url, path.startsWith("/") ? path : "/" + path);
    }

    /** AgentScope builder 需要分离后的服务根和请求路径。 */
    public record ModelEndpoint(String baseUrl, String endpointPath) {}

    /**
     * 从冻结版本的父目录创建只读 Skill repository。
     *
     * @param versionRuntimeRoot 形如 {@code runtime/<versionId>} 的装载根，其下必须再包含 Skill 子目录
     */
    public static FileSystemSkillRepository readOnlySkillRepository(Path versionRuntimeRoot) {
        return new FileSystemSkillRepository(versionRuntimeRoot, false);
    }

    /**
     * 为受限事实核验 Agent 创建无需逐次确认的权限上下文。
     *
     * <p>BYPASS 只作用于该 Agent 已注册的 Toolkit；调用方必须继续禁用元工具、任务列表和 Skill 代码执行，并确保 MCP Server
     * 只暴露已经过只读门禁的六个白名单工具。若以后增加写工具，必须先撤销该模式并重新设计精确权限规则。
     */
    public static PermissionContextState restrictedAgentPermissionContext() {
        return PermissionContextState.builder().mode(PermissionMode.BYPASS).build();
    }

    /**
     * 为一次核验或评测运行创建 Streamable HTTP MCP builder。
     *
     * <p>快照 ID 使用静态 header 固定在该 client 生命周期，禁止模型把快照选择作为工具参数传入。调用方必须继续 {@code
     * buildAsync()} 并在运行结束关闭返回的 client。
     */
    public static McpClientBuilder streamableMcpBuilder(String mcpEndpoint, String snapshotId) {
        return McpClientBuilder.create("enterprise-evidence-" + snapshotId)
                .streamableHttpTransport(mcpEndpoint)
                .protocolVersions(MCP_PROTOCOL_VERSION)
                .header("X-Evidence-Snapshot-Id", snapshotId)
                .timeout(Duration.ofSeconds(60));
    }
}
