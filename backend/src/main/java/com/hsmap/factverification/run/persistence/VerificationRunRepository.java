package com.hsmap.factverification.run.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 保存 PRIMARY/SHADOW 独立运行；两者通过数据库唯一键保持一任务各一条。 */
@Repository
public class VerificationRunRepository {

    private final JdbcTemplate jdbcTemplate;

    public VerificationRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 创建时钉死 Skill、模型、工具、输出契约和证据快照。 */
    public void insert(NewRun run) {
        jdbcTemplate.update(
                """
                insert into test.verification_run
                  (id, task_id, run_type, variant_type, skill_version_id, model_config_hash,
                   tool_contract_hash, output_schema_hash, evidence_snapshot_id,
                   status, shadow_review_status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING',
                        case when ? = 'SHADOW' then 'PENDING' else null end, ?)
                """,
                run.id(),
                run.taskId(),
                run.runType(),
                run.variantType(),
                run.skillVersionId(),
                run.modelConfigHash(),
                run.toolContractHash(),
                run.outputSchemaHash(),
                run.evidenceSnapshotId(),
                run.runType(),
                run.createdAt());
    }

    /** 查询任务下指定类型运行，避免把影子结果误当正式结果。 */
    public Optional<RunState> findByTaskAndType(UUID taskId, String runType) {
        return jdbcTemplate
                .query(
                        """
                        select id, task_id, run_type, variant_type, skill_version_id, evidence_snapshot_id, status,
                               result_json::text
                          from test.verification_run
                         where task_id = ? and run_type = ?
                        """,
                        (rs, rowNum) -> new RunState(
                                rs.getObject("id", UUID.class),
                                rs.getObject("task_id", UUID.class),
                                rs.getString("run_type"),
                                rs.getString("variant_type"),
                                rs.getObject("skill_version_id", UUID.class),
                                rs.getObject("evidence_snapshot_id", UUID.class),
                                rs.getString("status"),
                                rs.getString("result_json")),
                        taskId,
                        runType)
                .stream()
                .findFirst();
    }

    /** 按主键读取运行，用于管理页面区分 PRIMARY/SHADOW。 */
    public Optional<RunState> findById(UUID id) {
        return jdbcTemplate
                .query(
                        """
                        select id, task_id, run_type, variant_type, skill_version_id, evidence_snapshot_id, status,
                               result_json::text
                          from test.verification_run where id = ?
                        """,
                        (rs, rowNum) -> new RunState(
                                rs.getObject("id", UUID.class),
                                rs.getObject("task_id", UUID.class),
                                rs.getString("run_type"),
                                rs.getString("variant_type"),
                                rs.getObject("skill_version_id", UUID.class),
                                rs.getObject("evidence_snapshot_id", UUID.class),
                                rs.getString("status"),
                                rs.getString("result_json")),
                        id)
                .stream()
                .findFirst();
    }

    /** 在 Agent 执行前记录开始时间。 */
    public int markRunning(UUID id, OffsetDateTime startedAt) {
        return jdbcTemplate.update(
                "update test.verification_run set status = 'RUNNING', started_at = ? where id = ? and status = 'PENDING'",
                startedAt,
                id);
    }

    /** 运行成功后写入一次结果；仅允许从 PENDING/RUNNING 完成，防止覆盖历史。 */
    public int complete(
            UUID id,
            String resultJson,
            String toolCallsJson,
            String modelUsageJson,
            long durationMs,
            OffsetDateTime finishedAt) {
        return jdbcTemplate.update(
                """
                update test.verification_run
                   set status = 'COMPLETED', result_json = ?::jsonb, tool_calls_json = ?::jsonb,
                       model_usage_json = ?::jsonb, duration_ms = ?, finished_at = ?
                 where id = ? and status in ('PENDING', 'RUNNING')
                """,
                resultJson,
                toolCallsJson,
                modelUsageJson,
                durationMs,
                finishedAt,
                id);
    }

    /**
     * 记录独立运行的脱敏失败摘要。
     *
     * <p>SHADOW 调用此方法时不会修改 verification_task，因此候选版本故障不会污染正式结果。
     */
    public int fail(UUID id, String errorCode, String errorSummary, OffsetDateTime finishedAt) {
        return jdbcTemplate.update(
                """
                update test.verification_run
                   set status = 'FAILED', error_code = ?, error_summary = ?, finished_at = ?
                 where id = ? and status in ('PENDING', 'RUNNING')
                """,
                errorCode,
                errorSummary,
                finishedAt,
                id);
    }

    /** 影子复核只允许从 PENDING 设置一次 PASS/FAIL。 */
    public int reviewShadow(UUID id, String status, String reason, String operator, OffsetDateTime reviewedAt) {
        return jdbcTemplate.update(
                """
                update test.verification_run
                   set shadow_review_status = ?, shadow_review_reason = ?,
                       shadow_reviewed_by = ?, shadow_reviewed_at = ?
                 where id = ? and run_type = 'SHADOW'
                   and shadow_review_status = 'PENDING'
                   and status = 'COMPLETED'
                """,
                status,
                reason,
                operator,
                reviewedAt,
                id);
    }

    /** Candidate 至少一条真实材料影子复核 PASS 才允许晋升。 */
    public boolean hasPassedShadow(UUID skillVersionId) {
        Boolean value = jdbcTemplate.queryForObject(
                """
                        select exists(
                            select 1 from test.verification_run
                             where run_type = 'SHADOW' and skill_version_id = ?
                               and status = 'COMPLETED' and shadow_review_status = 'PASS'
                        )
                        """,
                Boolean.class,
                skillVersionId);
        return Boolean.TRUE.equals(value);
    }

    /**
     * 管理页读取真实影子历史，并按同 ordinal 主张统计结论一致/差异。
     *
     * <p>这是只读查询投影，不把没有金标的真实任务包装成准确率。FULL JOIN 前必须先把两侧分别限定为当前 SHADOW 与 PRIMARY；
     * 如果直接连接整张 claim 表再过滤单侧，右表中的 SHADOW 主张会再次成为未匹配行，导致相同结论也被错误计入差异。
     */
    public List<ShadowRunRow> listShadowRuns() {
        return jdbcTemplate.query(
                """
                select t.id as task_id,
                       t.original_file_name,
                       coalesce(companies.company_names, '') as company_names,
                       p.id as primary_run_id,
                       s.id as shadow_run_id,
                       p.skill_version_id as stable_version_id,
                       s.skill_version_id as candidate_version_id,
                       p.status as primary_status,
                       s.status as shadow_status,
                       coalesce(s.shadow_review_status, 'PENDING') as shadow_review_status,
                       coalesce(diff.agreement_count, 0) as agreement_count,
                       coalesce(diff.difference_count, 0) as difference_count,
                       s.created_at
                  from test.verification_run s
                  join test.verification_task t on t.id = s.task_id
                  left join test.verification_run p
                    on p.task_id = s.task_id and p.run_type = 'PRIMARY'
                  left join lateral (
                      select string_agg(distinct c.company_name, ', ' order by c.company_name) as company_names
                        from test.claim c
                       where c.run_id in (p.id, s.id) and c.company_name is not null
                  ) companies on true
                  left join lateral (
                      select count(*) filter (
                                 where pc.verification_status = sc.verification_status
                               )::integer as agreement_count,
                             count(*) filter (
                                 where pc.verification_status is distinct from sc.verification_status
                               )::integer as difference_count
                        from (select * from test.claim where run_id = s.id) sc
                        full join (select * from test.claim where run_id = p.id) pc
                          on pc.ordinal = sc.ordinal
                  ) diff on true
                 where s.run_type = 'SHADOW'
                 order by s.created_at desc, s.id
                """,
                (rs, rowNum) -> new ShadowRunRow(
                        rs.getObject("task_id", UUID.class),
                        rs.getString("original_file_name"),
                        rs.getString("company_names"),
                        rs.getObject("primary_run_id", UUID.class),
                        rs.getObject("shadow_run_id", UUID.class),
                        rs.getObject("stable_version_id", UUID.class),
                        rs.getObject("candidate_version_id", UUID.class),
                        rs.getString("primary_status"),
                        rs.getString("shadow_status"),
                        rs.getString("shadow_review_status"),
                        rs.getInt("agreement_count"),
                        rs.getInt("difference_count"),
                        rs.getObject("created_at", OffsetDateTime.class)));
    }

    public record NewRun(
            UUID id,
            UUID taskId,
            String runType,
            String variantType,
            UUID skillVersionId,
            String modelConfigHash,
            String toolContractHash,
            String outputSchemaHash,
            UUID evidenceSnapshotId,
            OffsetDateTime createdAt) {}

    public record RunState(
            UUID id,
            UUID taskId,
            String runType,
            String variantType,
            UUID skillVersionId,
            UUID evidenceSnapshotId,
            String status,
            String resultJson) {}

    /** 管理影子页所需的最小只读投影。 */
    public record ShadowRunRow(
            UUID taskId,
            String fileName,
            String companyNames,
            UUID primaryRunId,
            UUID shadowRunId,
            UUID stableVersionId,
            UUID candidateVersionId,
            String primaryStatus,
            String shadowStatus,
            String reviewStatus,
            int agreementCount,
            int differenceCount,
            OffsetDateTime createdAt) {}
}
