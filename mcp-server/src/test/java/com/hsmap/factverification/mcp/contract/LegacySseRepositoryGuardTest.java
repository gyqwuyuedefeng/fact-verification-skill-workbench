package com.hsmap.factverification.mcp.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 对两个 Java 应用的生产链路执行旧 MCP SSE 仓库级静态门禁。 */
class LegacySseRepositoryGuardTest {

    private static final List<String> FORBIDDEN = List.of(
            "sseTransport(",
            "WebMvcSseServerTransportProvider",
            "SSEServerTransport",
            "protocol: SSE",
            "/mcp/message",
            "\"/sse\"");

    /** 只扫描生产源码；浏览器业务 SseEmitter 不属于 MCP transport，允许保留。 */
    @Test
    void productionMcpChainContainsNoLegacySseImplementation() throws Exception {
        List<Path> roots = List.of(Path.of("src/main"), Path.of("../backend/src/main"));
        for (Path root : roots) {
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (file.endsWith("src/main/resources/contracts/mcp-tools.json")) {
                        continue;
                    }
                    String text = Files.readString(file);
                    for (String forbidden : FORBIDDEN) {
                        assertThat(text)
                                .as("旧 MCP SSE 标记 %s 出现在 %s", forbidden, file)
                                .doesNotContain(forbidden);
                    }
                }
            }
        }
    }
}
