package com.hsmap.factverification.mcp.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 防止旧 MCP SSE 双端点代码被复制进新服务。
 *
 * <p>只扫描生产源码和配置，测试代码可以保留负向断言；依赖 jar 内存在已废弃类型不代表本项目启用了对应链路。
 */
class LegacySseStaticGuardTest {

    private static final String APPROVED_CONTRACT = "src/main/resources/contracts/mcp-tools.json";

    private static final List<String> FORBIDDEN = List.of(
            "protocol=SSE",
            "protocol: SSE",
            "/sse",
            "/mcp/message",
            "EventSource",
            "WebMvcSseServerTransportProvider",
            "SSEServerTransport");

    /** main 目录只能出现 Streamable HTTP 配置和 `/mcp` 单端点。 */
    @Test
    void productionSourcesContainNoLegacyMcpTransport() throws IOException {
        Path moduleRoot = Path.of("").toAbsolutePath().normalize();
        List<Path> roots = List.of(moduleRoot.resolve("src/main/java"), moduleRoot.resolve("src/main/resources"));

        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    String relativePath = moduleRoot.relativize(file).toString().replace('\\', '/');
                    if (APPROVED_CONTRACT.equals(relativePath)) {
                        assertThat(content)
                                .as("工具契约必须明确声明 Streamable HTTP 单端点和旧端点禁令")
                                .contains("\"protocol\": \"STREAMABLE\"")
                                .contains("\"endpoint\": \"/mcp\"")
                                .contains("\"legacyEndpointsForbidden\"")
                                .contains("\"/sse\"")
                                .contains("\"/mcp/message\"");
                        continue;
                    }
                    for (String forbidden : FORBIDDEN) {
                        assertThat(content)
                                .as("%s 不得包含 %s", relativePath, forbidden)
                                .doesNotContain(forbidden);
                    }
                }
            }
        }
    }
}
