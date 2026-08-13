package com.hsmap.factverification.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.config.WorkbenchProperties;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 锁定升级说明客户端在最小后端依赖中可直接装配，不假设容器额外提供 RestClient.Builder。 */
class OpenAiSkillChangeSummaryClientContextTest {

    /**
     * 测试场景：只提供客户端真正需要的工作台配置和 JSON 解析器。
     * 前置条件：Spring Boot 当前 WebMVC starter 不保证注册 RestClient.Builder Bean。
     * 期望结果：客户端仍可被容器创建，模型网络请求只在管理员点击时发生。
     * 断言重点：启动应用不应因为可选的 AI 审核辅助而失败。
     */
    @Test
    void startsWithoutContainerManagedRestClientBuilder() {
        WorkbenchProperties properties = new WorkbenchProperties(
                new WorkbenchProperties.DatabaseBoundary("kjjr_inx_brain", "test", false),
                Path.of("data"),
                Path.of("evals/manifest.json"),
                Path.of("skills/company-material-fact-check"),
                new WorkbenchProperties.Model(
                        "http://127.0.0.1:48080/v1/chat/completions", "/v1/chat/completions", "company-qwen", ""),
                URI.create("http://127.0.0.1:19091/mcp"));

        new ApplicationContextRunner()
                .withBean(WorkbenchProperties.class, () -> properties)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(OpenAiSkillChangeSummaryClient.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OpenAiSkillChangeSummaryClient.class);
                });
    }
}
