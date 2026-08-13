package com.hsmap.factverification.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 对比赛隔离区迁移脚本进行无数据库副作用的契约检查。
 *
 * <p>共享测试库不能由测试客户端清空或随意建表，因此这里验证静态迁移边界；真实建表只允许在应用以 Flyway 启动时发生。
 */
class MigrationContractTest {

    private static final List<String> TABLES = List.of(
            "verification_task",
            "verification_run",
            "claim",
            "skill_version",
            "evaluation_run",
            "evidence_snapshot",
            "release_binding");

    /** 七张且仅七张比赛表都必须显式写入 test schema。 */
    @Test
    void migrationCreatesSevenTablesOnlyInsideTestSchema() throws IOException {
        String migration = resourceText("db/migration/V1__create_fact_verification_workbench.sql");

        for (String table : TABLES) {
            assertThat(migration).contains("CREATE TABLE test." + table);
        }
        assertThat(migration).doesNotContain("CREATE SCHEMA");
        assertThat(migration).doesNotContain(" public.");
    }

    /** 关键唯一约束保证幂等、快照复用和追加发布 revision 不会重复。 */
    @Test
    void migrationContainsCurrentMvpUniquenessBoundaries() throws IOException {
        String migration = resourceText("db/migration/V1__create_fact_verification_workbench.sql");

        assertThat(migration)
                .contains("UNIQUE (request_id)")
                .contains("UNIQUE (task_id, run_type)")
                .contains("UNIQUE (run_id, ordinal)")
                .contains("UNIQUE (snapshot_id, tool_name, arguments_hash)")
                .contains("UNIQUE (skill_key, revision)");
    }

    /** 对话增量只能扩展既有任务/运行表，不得新增第八张业务表或伪造 BASELINE Skill。 */
    @Test
    void chatMigrationKeepsSevenTableBoundaryAndModelsBaselineExplicitly() throws IOException {
        String migration = resourceText("db/migration/V2__chat_verification.sql");

        assertThat(migration)
                .contains("ALTER TABLE test.verification_task")
                .contains("ADD COLUMN user_message text")
                .contains("ADD COLUMN input_type varchar(16)")
                .contains("ALTER TABLE test.verification_run")
                .contains("ADD COLUMN variant_type varchar(16)")
                .contains("variant_type = 'BASELINE' AND skill_version_id IS NULL")
                .doesNotContain("CREATE TABLE");
    }

    /** Flyway 必须固定到 test schema，并永久禁用 clean。 */
    @Test
    void applicationConfigurationPinsFlywayAndDisablesClean() throws IOException {
        String configuration = resourceText("application.yml");

        assertThat(configuration)
                .contains("default-schema: test")
                .contains("schemas: test")
                .contains("clean-disabled: true")
                .contains("create-schemas: false");
    }

    /**
     * 测试场景：应用使用 Spring Boot 4 启动时必须具备 Flyway 自动配置模块。
     * 前置条件：迁移 SQL 和 {@code spring.flyway} 配置已存在，但不能把二者误当作自动执行保证。
     * 期望结果：Boot 4 的 Flyway 自动配置类位于运行时类路径，启动阶段才能创建迁移初始化器。
     * 断言重点：防止只引入 {@code flyway-core} 导致应用健康启动却没有创建比赛表的回归。
     */
    @Test
    void runtimeClasspathIncludesSpringBootFlywayAutoConfiguration() {
        assertThat(org.springframework.util.ClassUtils.isPresent(
                        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                        getClass().getClassLoader()))
                .isTrue();
    }

    /**
     * 测试场景：审核人按照 Quickstart 从工作台根目录启动后端。
     * 前置条件：未显式覆盖三个本地文件路径，应用使用仓库内默认值。
     * 期望结果：默认路径直接指向工作台根目录下的 data、evals 和 skills，Boot 4 可以绑定为 Path。
     * 断言重点：禁止使用会被 Boot 4 资源路径规范化拒绝的父目录跳转 {@code ../}。
     */
    @Test
    void defaultWorkbenchPathsAreValidFromDocumentedWorkingDirectory() throws IOException {
        String configuration = resourceText("application.yml");

        assertThat(configuration)
                .contains("storage-root: ${WORKBENCH_STORAGE_ROOT:data}")
                .contains("evaluation-manifest: ${EVALUATION_MANIFEST_PATH:evals/manifest.json}")
                .contains("skill-source-root: ${SKILL_SOURCE_ROOT:skills/company-material-fact-check}")
                .doesNotContain(":../");
    }

    private String resourceText(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
