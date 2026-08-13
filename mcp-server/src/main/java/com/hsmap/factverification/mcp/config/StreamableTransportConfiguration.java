package com.hsmap.factverification.mcp.config;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import java.util.Map;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * 为原生 Streamable HTTP transport 提取证据快照请求头。
 *
 * <p>Spring AI 默认 provider 不保留业务请求头，因此仅在这里等价创建同一种 Streamable provider；端点和保活参数仍来自官方配置属性。
 */
@Configuration
public class StreamableTransportConfiguration {

    public static final String SNAPSHOT_HEADER = "X-Evidence-Snapshot-Id";
    public static final String SNAPSHOT_CONTEXT_KEY = "evidenceSnapshotId";

    /** 创建唯一 `/mcp` provider，并把静态快照头放入单次 MCP transport context。 */
    @Bean
    WebMvcStreamableServerTransportProvider evidenceStreamableTransportProvider(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(properties.getMcpEndpoint())
                .keepAliveInterval(properties.getKeepAliveInterval())
                .disallowDelete(properties.isDisallowDelete())
                .contextExtractor(request -> {
                    String snapshotId = request.headers().firstHeader(SNAPSHOT_HEADER);
                    return snapshotId == null
                            ? McpTransportContext.EMPTY
                            : McpTransportContext.create(Map.of(SNAPSHOT_CONTEXT_KEY, snapshotId));
                })
                .build();
    }
}
