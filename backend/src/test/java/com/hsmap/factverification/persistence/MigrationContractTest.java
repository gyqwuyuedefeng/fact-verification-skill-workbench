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

    /**
     * 测试场景：首次迁移创建事实核验工作台的业务表。
     * 前置条件：V1 是工作台唯一的初始表结构迁移，测试以静态文本读取脚本。
     * 期望结果：七张业务表均显式位于 test schema，且迁移不创建 schema 或引用 public schema。
     * 断言重点：比赛数据边界必须与共享数据库的其他 schema 隔离。
     */
    @Test
    void migrationCreatesSevenTablesOnlyInsideTestSchema() throws IOException {
        String migration = resourceText("db/migration/V1__create_fact_verification_workbench.sql");

        for (String table : TABLES) {
            assertThat(migration).contains("CREATE TABLE test." + table);
        }
        assertThat(migration).doesNotContain("CREATE SCHEMA");
        assertThat(migration).doesNotContain(" public.");
    }

    /**
     * 测试场景：并发或重试请求命中初始数据模型的关键唯一边界。
     * 前置条件：V1 已定义任务、运行、快照和版本发布相关表。
     * 期望结果：脚本保留请求幂等、运行唯一、证据快照复用和 Skill revision 的唯一约束。
     * 断言重点：避免迁移调整时无意移除会导致重复处理或错误复用的数据库保护。
     */
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

    /**
     * 测试场景：对话核验功能在既有工作台数据模型上增加输入与运行变体。
     * 前置条件：V1 已创建七张业务表，V2 只应扩展 verification_task 和 verification_run。
     * 期望结果：V2 明确表达 BASELINE 可没有 Skill 版本，且不创建第八张业务表。
     * 断言重点：对话增量不能破坏原有表数边界或伪造不存在的基准 Skill。
     */
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

    /**
     * 测试场景：Skill 升级说明需要随目标冻结版本持久化，供页面刷新后恢复。
     * 前置条件：V1 已创建 skill_version，V2 仅扩展对话相关字段。
     * 期望结果：V3 只向既有 skill_version 增加以基础版本 UUID 为键的 JSONB 字段。
     * 断言重点：禁止为可恢复的审核说明新增业务表，也禁止改变既有表的归属 schema。
     */
    @Test
    void comparisonMigrationOnlyAddsJsonColumnToExistingSkillVersionTable() throws IOException {
        String migration = resourceText("db/migration/V3__persist_skill_version_comparisons.sql");

        assertThat(migration)
                .contains("ALTER TABLE test.skill_version")
                .contains("ADD COLUMN comparison_summaries_json jsonb NOT NULL DEFAULT '{}'::jsonb")
                .doesNotContain("CREATE TABLE")
                .doesNotContain("CREATE SCHEMA");
    }

    /**
     * 测试场景：应用启动时执行 Flyway 迁移。
     * 前置条件：共享数据库中存在其他业务 schema，测试环境不允许清空表。
     * 期望结果：Flyway 固定迁移到 test schema，禁止 clean 且不自动创建 schema。
     * 断言重点：防止错误配置扩大迁移范围或执行不可逆的清理操作。
     */
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
