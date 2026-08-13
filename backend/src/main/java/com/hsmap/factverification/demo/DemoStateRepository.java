package com.hsmap.factverification.demo;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 比赛演示状态的固定七表仓储。
 *
 * <p>所有 SQL 在此处固定，调用方没有表名、schema 或删除顺序的输入入口，避免演示能力演变为通用清库工具。
 */
@Repository
public class DemoStateRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 注入工作台既有数据源，所有状态查询和清理均通过应用仓储执行。 */
    public DemoStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** PENDING/RUNNING 的评测仍可能写入证据或结果，不能在其运行期间删除数据。 */
    public boolean hasActiveEvaluations() {
        Boolean active = jdbcTemplate.queryForObject(
                "select exists(select 1 from test.evaluation_run where status in ('PENDING', 'RUNNING'))",
                Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * 核验解析、核验运行均可能继续访问任务、版本和文件；任一活动状态都必须阻止清空。
     *
     * <p>任务只检查 PARSING/RUNNING，运行检查 PENDING/RUNNING，严格对应当前生命周期中仍会产生业务写入的状态。
     */
    public boolean hasActiveVerificationWork() {
        Boolean active = jdbcTemplate.queryForObject(
                """
                select exists(select 1 from test.verification_task where status in ('PARSING', 'RUNNING'))
                    or exists(select 1 from test.verification_run where status in ('PENDING', 'RUNNING'))
                """,
                Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /** 读取固定七表计数，供演示页展示和快照导入前的空状态检查复用。 */
    public Map<String, Long> counts() {
        return jdbcTemplate.queryForObject(
                """
                select (select count(*) from test.claim) as claim,
                       (select count(*) from test.verification_run) as verification_run,
                       (select count(*) from test.verification_task) as verification_task,
                       (select count(*) from test.evidence_snapshot) as evidence_snapshot,
                       (select count(*) from test.release_binding) as release_binding,
                       (select count(*) from test.skill_version) as skill_version,
                       (select count(*) from test.evaluation_run) as evaluation_run
                """,
                (resultSet, rowNum) -> {
                    Map<String, Long> counts = new LinkedHashMap<>();
                    counts.put("claim", resultSet.getLong("claim"));
                    counts.put("verification_run", resultSet.getLong("verification_run"));
                    counts.put("verification_task", resultSet.getLong("verification_task"));
                    counts.put("evidence_snapshot", resultSet.getLong("evidence_snapshot"));
                    counts.put("release_binding", resultSet.getLong("release_binding"));
                    counts.put("skill_version", resultSet.getLong("skill_version"));
                    counts.put("evaluation_run", resultSet.getLong("evaluation_run"));
                    return Map.copyOf(counts);
                });
    }

    /**
     * 在同一数据库事务中按既有外键顺序清理比赛数据。
     *
     * <p>skill_version 在删除前先解除自引用和注册评测引用，随后才能清理版本及其父级 evaluation_run；顺序不得由调用方改变。
     */
    public void clearAll() {
        jdbcTemplate.update("delete from test.claim");
        jdbcTemplate.update("delete from test.verification_run");
        jdbcTemplate.update("delete from test.verification_task");
        jdbcTemplate.update("delete from test.evidence_snapshot");
        jdbcTemplate.update("delete from test.release_binding");
        jdbcTemplate.update("update test.skill_version set parent_version_id = null, registered_evaluation_id = null");
        jdbcTemplate.update("delete from test.skill_version");
        jdbcTemplate.update("delete from test.evaluation_run");
    }
}
