package com.hsmap.factverification.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 防止真实数据库、ES 或模型凭据被写进应用配置默认值。 */
class SensitiveDataGuardTest {

    private static final Pattern PLAIN_SECRET =
            Pattern.compile("(?im)^\\s*(password|api-key|token):\\s+(?!\\$\\{)[^#\\s]+\\s*$");
    private static final Pattern JDBC_USER_INFO = Pattern.compile("jdbc:[a-z]+://[^/@\\s]+:[^/@\\s]+@");

    /** 两个应用的生产源码配置只能引用环境变量，不能保存可用 secret。 */
    @Test
    void productionConfigurationContainsNoPlaintextCredentials() throws Exception {
        List<Path> roots = List.of(Path.of("src/main/resources"), Path.of("../mcp-server/src/main/resources"));
        for (Path root : roots) {
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String text = Files.readString(file);
                    assertThat(PLAIN_SECRET.matcher(text).find())
                            .as("明文 secret: %s", file)
                            .isFalse();
                    assertThat(JDBC_USER_INFO.matcher(text).find())
                            .as("JDBC user-info: %s", file)
                            .isFalse();
                }
            }
        }
    }
}
