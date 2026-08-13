package com.hsmap.factverification.mcp.compat;

import static org.assertj.core.api.Assertions.assertThat;

import com.hsmap.factverification.mcp.EnterpriseEvidenceMcpApplication;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcSseServerTransportProvider;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * 锁定 Spring AI 2.0.0 的原生 Streamable HTTP 服务端选择结果。
 *
 * <p>同一个 starter 同时包含新旧 transport，因而不能只检查依赖存在；必须从实际 Spring 上下文证明只有 Streamable provider 被创建。
 */
@SpringBootTest(
        classes = EnterpriseEvidenceMcpApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.ai.mcp.server.protocol=STREAMABLE",
            "spring.ai.mcp.server.name=enterprise-evidence-test",
            "spring.ai.mcp.server.capabilities.resource=false",
            "spring.ai.mcp.server.capabilities.prompt=false",
            "spring.ai.mcp.server.capabilities.completion=false",
            "spring.datasource.url=jdbc:h2:mem:mcpcompat;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.hikari.read-only=true",
            "enterprise-evidence.snapshot.verify-on-startup=false",
            "spring.flyway.enabled=false"
        })
class SpringAiMcpCompatibilityTest {

    @Autowired
    ApplicationContext applicationContext;

    /** 新旧 transport 类都可在 classpath，但运行时只能实例化原生 Streamable provider。 */
    @Test
    void createsOnlyStreamableHttpTransportProvider() {
        assertThat(applicationContext.getBeansOfType(WebMvcStreamableServerTransportProvider.class))
                .hasSize(1);
        assertThat(applicationContext.getBeansOfType(WebMvcSseServerTransportProvider.class))
                .isEmpty();
    }
}
