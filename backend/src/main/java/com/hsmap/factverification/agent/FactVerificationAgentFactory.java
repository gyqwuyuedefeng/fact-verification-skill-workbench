package com.hsmap.factverification.agent;

import com.hsmap.factverification.compat.AgentScopeRuntimeCompatibility;
import com.hsmap.factverification.config.WorkbenchProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.stereotype.Component;

/** 以锁定的公司千问配置创建一个最小 ReActAgent，不启用子 Agent、记忆或沙箱。 */
@Component
public final class FactVerificationAgentFactory {

    /**
     * Run Manifest 使用的非敏感运行时身份。
     *
     * <p>依赖版本本身不足以区分“由模型动态加载 Skill”和“将冻结 Skill 确定性注入系统提示词”两种执行语义。
     * 因此这里把本项目实际采用的注入策略一并纳入哈希，使架构变更后的评测不会被误认为与旧批次条件完全相同。
     */
    public static final String RUNTIME_IDENTITY =
            "agentscope-java:2.0.1;skill-injection=frozen-content-with-references-v2;"
                    + "first-tool=resolve_company-specific-v1;required-tool-retry=1;result-schema-retry=1";

    private final WorkbenchProperties properties;
    private final FrozenSkillLoader skillLoader = new FrozenSkillLoader();

    public FactVerificationAgentFactory(WorkbenchProperties properties) {
        this.properties = properties;
    }

    /** BASELINE 与 Skill 共享同一个模型和 Toolkit；仅 system prompt/Skill 内容不同。 */
    public ReActAgent create(AgentVariant variant, Toolkit toolkit) {
        WorkbenchProperties.Model modelConfig = properties.model();
        OpenAIChatModel model = AgentScopeRuntimeCompatibility.openAiCompatibleModel(
                modelConfig.url(), modelConfig.endpointPath(), modelConfig.id(), modelConfig.apiKey());
        String systemPrompt = variant.systemPrompt();
        if ("SKILL".equals(variant.type())) {
            String frozenContent = skillLoader.loadContent(variant.skillRuntimeRoot(), variant.contentHash());
            // 单 Skill MVP 将通过仓库/hash 门禁的正文和 references 确定性注入。这避免公司模型先调用
            // load_skill_through_path 后偶发跳过规则，同时保留真实 Skill 文件、版本和整目录篡改校验边界。
            systemPrompt = systemPrompt + "\n\n--- 已冻结 Skill 正文 ---\n" + frozenContent;
        }
        ReActAgent.Builder builder = ReActAgent.builder()
                .name("company-material-fact-check")
                .description("企业材料事实核验")
                .sysPrompt(systemPrompt)
                .model(model)
                .toolkit(toolkit)
                .middleware(new RequiredCompanyResolutionMiddleware())
                .generateOptions(AgentRuntimeParameters.generateOptions())
                .maxIters(12)
                .enableMetaTool(false)
                .enableTaskList(false)
                .skillCodeExecutionEnabled(false)
                .dynamicSkillsEnabled(false)
                .permissionContext(AgentScopeRuntimeCompatibility.restrictedAgentPermissionContext());
        return builder.build();
    }
}
