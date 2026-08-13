package com.hsmap.factverification.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 被测试对象：生产配置、比赛快照、内置 fixture 与三阶段 Skill 预置的敏感数据边界。
 * 测试目的：防止真实数据库、ES 或模型凭据写入默认配置、内置资产，或被快照实现作为可导出来源引用。
 * 覆盖范围：两个应用资源配置、demo-state 资源、skills/presets 和快照服务源码中的凭据引用。
 * 前置条件：仅只读扫描工作区源码和资源，不加载环境变量、不访问外部服务。
 */
class SensitiveDataGuardTest {

    private static final Pattern PLAIN_SECRET =
            Pattern.compile("(?im)^\\s*(password|api-key|token):\\s+(?!\\$\\{)[^#\\s]+\\s*$");
    private static final Pattern JDBC_USER_INFO = Pattern.compile("jdbc:[a-z]+://[^/@\\s]+:[^/@\\s]+@");

    /**
     * 测试场景：扫描两个应用的生产源码配置。
     * 前置条件：配置允许以 ${ENV_NAME} 形式引用环境变量。
     * 期望结果：不存在明文 secret 或 JDBC user-info。
     * 断言重点：默认配置不能保存可直接使用的真实凭据。
     */
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

    /**
     * 测试场景：快照生产实现选择导出来源。
     * 前置条件：快照只应遍历固定受管目录和固定七表，配置及凭据不属于比赛状态。
     * 期望结果：实现源码不引用三个凭据变量名，也不把 src/main/resources 作为归档根。
     * 断言重点：后续维护不能为“方便恢复”把开发机配置或凭据扩进快照。
     */
    @Test
    void snapshotImplementationDoesNotReferenceCredentialsOrConfigurationRoot() throws Exception {
        String source =
                Files.readString(Path.of("src/main/java/com/hsmap/factverification/demo/SnapshotArchiveService.java"));

        assertThat(source)
                .doesNotContain("APP_DB_PASSWORD")
                .doesNotContain("ES_PASSWORD")
                .doesNotContain("LOCAL_MODEL_API_KEY")
                .doesNotContain("src/main/resources");
    }

    /**
     * 测试场景：扫描新增的脱敏 fixture 与三套预置正文。
     * 前置条件：资源只允许保存固定业务 UUID、公开演示材料内容和 Skill 规则，不允许凭据字段或 JDBC user-info。
     * 期望结果：所有新增文本都不包含明文 secret、连接串账号段或常见凭据字段。
     * 断言重点：被 Git 跟踪的内置演示资产不能因为“仅 test profile 使用”而放宽敏感数据门禁。
     */
    @Test
    void builtinFixtureAndSkillPresetsContainNoSensitiveData() throws Exception {
        List<Path> roots = List.of(Path.of("src/main/resources/demo-state"), Path.of("../skills/presets"));
        for (Path root : roots) {
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String text = Files.readString(file);
                    assertThat(text)
                            .as("内置资产敏感字段: %s", file)
                            .doesNotContain("jdbc:", "APP_DB_PASSWORD", "ES_PASSWORD", "LOCAL_MODEL_API_KEY")
                            .doesNotMatch("(?is).*\\\"(?:username|password|api[-_]?key)\\\"\\s*:.*");
                    assertThat(JDBC_USER_INFO.matcher(text).find())
                            .as("内置资产 JDBC user-info: %s", file)
                            .isFalse();
                }
            }
        }
    }
}
