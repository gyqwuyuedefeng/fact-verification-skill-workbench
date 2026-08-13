package com.hsmap.factverification.demo;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
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
     * 按主键稳定顺序流式读取指定白名单表的 PostgreSQL JSONB 复合行。
     *
     * <p>查询字符串只能从 SnapshotTable 构造；消费端每次只持有一行，避免把完整数据库快照先聚合到内存。
     */
    public void exportRows(SnapshotTable table, JsonRowConsumer consumer) throws IOException {
        String sql = "select to_jsonb(row_value)::text from test." + table.tableName() + " row_value order by id";
        try {
            jdbcTemplate.query(sql, (RowCallbackHandler) resultSet -> {
                try {
                    consumer.accept(resultSet.getString(1));
                } catch (IOException exception) {
                    throw new JsonRowAccessException(exception);
                }
            });
        } catch (JsonRowAccessException exception) {
            throw exception.getCause();
        }
    }

    /**
     * 将一行已校验 JSON 回填到枚举指定的 PostgreSQL 复合行类型。
     *
     * <p>jsonb_populate_record 让 UUID、timestamptz、JSONB 与可空字段由数据库按真实迁移类型恢复，避免 Java 侧猜测列类型。
     */
    public void insertRow(SnapshotTable table, String json) {
        String sql =
                "insert into test." + table.tableName() + " select imported.* from jsonb_populate_record(null::test."
                        + table.tableName() + ", ?::jsonb) imported";
        jdbcTemplate.update(sql, json);
    }

    /**
     * 在 evaluation_run 已完成导入后恢复 Skill 的自引用与注册评测引用。
     *
     * <p>两个目标 UUID 可为空；更新对象只能是已由固定 Skill JSONL 插入的主键。
     */
    public void restoreSkillReferences(UUID id, UUID parentVersionId, UUID registeredEvaluationId) {
        jdbcTemplate.update(
                "update test.skill_version set parent_version_id = ?, registered_evaluation_id = ? where id = ?",
                parentVersionId,
                registeredEvaluationId,
                id);
    }

    /**
     * 在 reset/import 独立事务内一次锁定固定七表，阻止未参与单进程文件协调器的普通 DB 写入穿过状态替换。
     *
     * <p>SQL 表名只由 {@link SnapshotTable#values()} 按固定顺序组成，不接受 manifest、HTTP 或方法参数的表标识符。
     * ACCESS EXCLUSIVE 只在 test-profile 管理事务的短时数据替换阶段持有，不覆盖 ZIP 网络上传或下载。
     */
    public void lockAllTablesForStateReplacement() {
        String tables = java.util.Arrays.stream(SnapshotTable.values())
                .map(table -> "test." + table.tableName())
                .collect(java.util.stream.Collectors.joining(", "));
        jdbcTemplate.execute("lock table " + tables + " in access exclusive mode");
    }

    /**
     * 恢复进程退出前 worker 尚未启动而遗留的超时 PRIMARY 运行及对应任务。
     *
     * <p>候选条件、一个小时时限、失败码和脱敏摘要全部固定在 SQL 中；调用方不能传入主键或阈值。数据修改 CTE
     * 先更新运行，再仅凭其 RETURNING 结果更新任务，使服务层可在同一事务提交前核对两类更新数量。
     */
    public StaleRecoveryCounts recoverStalePendingPrimaryRuns() {
        return jdbcTemplate.queryForObject(
                """
                with candidates as materialized (
                    select r.id as run_id, t.id as task_id
                      from test.verification_task t
                      join test.verification_run r on r.task_id = t.id
                     where t.status = 'RUNNING'
                       and r.run_type = 'PRIMARY'
                       and r.status = 'PENDING'
                       and r.started_at is null
                       and r.created_at < CURRENT_TIMESTAMP - interval '1 hour'
                       and not exists (
                           select 1
                             from test.verification_run other
                            where other.task_id = t.id
                              and other.id <> r.id
                              and other.status in ('PENDING', 'RUNNING')
                       )
                ),
                updated_runs as (
                    update test.verification_run r
                       set status = 'FAILED',
                           error_code = 'STALE_RUN_RECOVERED',
                           error_summary = '测试管理入口已回收进程退出前尚未启动的遗留核验运行',
                           finished_at = CURRENT_TIMESTAMP
                      from candidates c
                     where r.id = c.run_id
                       and r.status = 'PENDING'
                    returning c.task_id
                ),
                updated_tasks as (
                    update test.verification_task t
                       set status = 'FAILED',
                           error_code = 'STALE_RUN_RECOVERED',
                           error_summary = '测试管理入口已回收进程退出前尚未启动的遗留核验任务',
                           updated_at = CURRENT_TIMESTAMP
                      from updated_runs r
                     where t.id = r.task_id
                       and t.status = 'RUNNING'
                    returning t.id
                )
                select (select count(*) from updated_tasks) as recovered_tasks,
                       (select count(*) from updated_runs) as recovered_runs
                """,
                (resultSet, rowNum) -> new StaleRecoveryCounts(
                        resultSet.getInt("recovered_tasks"), resultSet.getInt("recovered_runs")));
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

    /** 允许流式 ZIP 写入把受检 IOException 保留到仓储调用边界。 */
    @FunctionalInterface
    public interface JsonRowConsumer {
        void accept(String json) throws IOException;
    }

    /** 固定恢复 SQL 的最小数量投影；服务层在事务提交前要求任务数和运行数严格一致。 */
    public record StaleRecoveryCounts(int recoveredTasks, int recoveredRuns) {}

    /** 在 JdbcTemplate 回调中短路传播受检 IOException，外层会还原原始异常。 */
    private static final class JsonRowAccessException extends RuntimeException {
        private JsonRowAccessException(IOException cause) {
            super(cause);
        }

        @Override
        public synchronized IOException getCause() {
            return (IOException) super.getCause();
        }
    }
}
