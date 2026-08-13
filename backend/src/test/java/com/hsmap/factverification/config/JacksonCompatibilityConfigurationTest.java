package com.hsmap.factverification.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 被测试对象：后端公共 Jackson 2 兼容配置。
 * 测试目的：防止 Spring Boot 4 仅装配 Jackson 3 时，依赖 Jackson 2 的 AgentScope 与业务组件因缺少
 * {@link ObjectMapper} 无法启动。
 * 覆盖范围：公共 ObjectMapper Bean 及依赖它的文档解析组件装配。
 * 前置条件：仅创建最小 Spring 上下文，不连接数据库、模型或 MCP。
 */
class JacksonCompatibilityConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(DocumentParserConfiguration.class);

    /**
     * 测试场景：加载文档解析配置时装配公共 Jackson 2 ObjectMapper。
     * 前置条件：上下文中不额外提供 ObjectMapper。
     * 期望结果：配置自身提供且仅提供一个兼容 Bean，解析器与哈希组件都能正常创建。
     * 断言重点：不能依赖 Spring Boot 4 的 Jackson 3 自动配置偶然提供同名类型。
     */
    @Test
    void providesJackson2ObjectMapperRequiredByBusinessComponents() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ObjectMapper.class);
            assertThat(context).hasSingleBean(com.hsmap.factverification.document.DeterministicDocumentParser.class);
            assertThat(context).hasSingleBean(com.hsmap.factverification.shared.CanonicalJsonHasher.class);
        });
    }
}
