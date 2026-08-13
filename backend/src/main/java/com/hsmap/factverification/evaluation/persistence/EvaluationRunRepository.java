package com.hsmap.factverification.evaluation.persistence;

import com.hsmap.factverification.persistence.JdbcJson;
import com.hsmap.factverification.release.BootstrapEvaluation;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 保存不可覆盖的同条件评测及报告；重跑评测必须创建新记录。 */
@Repository
public class EvaluationRunRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcJson jdbcJson;

    public EvaluationRunRepository(JdbcTemplate jdbcTemplate, JdbcJson jdbcJson) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcJson = jdbcJson;
    }

    /** 初始发布门禁仅读取 PASS 状态和参与对照的变体标识。 */
    public Optional<BootstrapEvaluation> findBootstrap(UUID id) {
        return jdbcTemplate
                .query(
                        """
                        select id, gate_status, variants_json::text
                          from test.evaluation_run where id = ?
                        """,
                        (rs, rowNum) -> new BootstrapEvaluation(
                                rs.getObject("id", UUID.class),
                                rs.getString("gate_status"),
                                jdbcJson.readVariantIdentifiers(rs.getString("variants_json"))),
                        id)
                .stream()
                .findFirst();
    }

    /** 根据幂等 requestId 查询既有评测，避免重复触发相同批次。 */
    public Optional<UUID> findIdByRequestId(String requestId) {
        return jdbcTemplate
                .query(
                        "select id from test.evaluation_run where request_id = ?",
                        (rs, rowNum) -> rs.getObject("id", UUID.class),
                        requestId)
                .stream()
                .findFirst();
    }

    /** 先保存完整清单与变体，后台执行只允许更新结果列。 */
    public void insertPending(NewEvaluation evaluation) {
        jdbcTemplate.update(
                """
                insert into test.evaluation_run
                  (id, request_id, dataset_version, dataset_hash, sample_count,
                   evidence_snapshot_id, run_manifest_json, variants_json, status,
                   gate_status, created_by, created_at)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'PENDING', 'PENDING', ?, ?)
                """,
                evaluation.id(),
                evaluation.requestId(),
                evaluation.datasetVersion(),
                evaluation.datasetHash(),
                evaluation.sampleCount(),
                evaluation.evidenceSnapshotId(),
                evaluation.runManifestJson(),
                evaluation.variantsJson(),
                evaluation.createdBy(),
                evaluation.createdAt());
    }

    /** 后台线程开始真实模型调用前转换为 RUNNING。 */
    public int markRunning(UUID id) {
        return jdbcTemplate.update(
                "update test.evaluation_run set status = 'RUNNING' where id = ? and status = 'PENDING'", id);
    }

    /**
     * 只允许第一次完成写入报告，保证重复回调不能覆盖已经用于发布门禁的评测资产。
     */
    public int complete(CompletedEvaluation evaluation) {
        return jdbcTemplate.update(
                """
                update test.evaluation_run
                   set sample_results_json = ?::jsonb,
                       metrics_json = ?::jsonb,
                       failures_json = ?::jsonb,
                       gate_status = ?,
                       gate_reasons_json = ?::jsonb,
                       report_markdown = ?,
                       report_json = ?::jsonb,
                       status = 'COMPLETED',
                       finished_at = ?
                 where id = ? and status = 'RUNNING' and report_json is null
                """,
                evaluation.sampleResultsJson(),
                evaluation.metricsJson(),
                evaluation.failuresJson(),
                evaluation.gateStatus(),
                evaluation.gateReasonsJson(),
                evaluation.reportMarkdown(),
                evaluation.reportJson(),
                evaluation.finishedAt(),
                evaluation.id());
    }

    /** 异常只保存稳定错误状态，不写入堆栈、凭据或模型响应全文。 */
    public void markFailed(UUID id) {
        jdbcTemplate.update(
                """
                update test.evaluation_run
                   set status = 'FAILED', gate_status = 'FAIL',
                       gate_reasons_json = '[{"name":"execution","passed":false,"reason":"评测执行失败"}]'::jsonb,
                       finished_at = now()
                 where id = ? and status in ('PENDING', 'RUNNING')
                """,
                id);
    }

    /**
     * 后端重启时把旧进程无法继续执行的评测收口为 INTERRUPTED。
     *
     * <p>评测工作线程仅存在于单实例 JVM 内，PENDING/RUNNING 不具备跨进程恢复能力。此更新只处理尚无报告的未完成记录，绝不改写已经完成并可用于发布门禁的评测资产。
     */
    public int interruptIncompleteAfterRestart() {
        return jdbcTemplate.update(
                """
                update test.evaluation_run
                   set status = 'INTERRUPTED', gate_status = 'FAIL',
                       gate_reasons_json =
                         '[{"name":"execution","passed":false,"reason":"后端重启，原评测执行已中断"}]'::jsonb,
                       finished_at = now()
                 where status in ('PENDING', 'RUNNING') and report_json is null
                """);
    }

    /** 读取评测概要和不可变报告。 */
    public Optional<EvaluationRow> find(UUID id) {
        return jdbcTemplate
                .query(
                        """
                        select id, dataset_version, dataset_hash, sample_count,
                               variants_json::text, run_manifest_json::text,
                               metrics_json::text, sample_results_json::text,
                               failures_json::text,
                               status, gate_status, gate_reasons_json::text,
                               report_markdown, report_json::text, created_at, finished_at
                          from test.evaluation_run where id = ?
                        """,
                        (rs, rowNum) -> new EvaluationRow(
                                rs.getObject("id", UUID.class),
                                rs.getString("dataset_version"),
                                rs.getString("dataset_hash"),
                                rs.getInt("sample_count"),
                                rs.getString("variants_json"),
                                rs.getString("run_manifest_json"),
                                rs.getString("metrics_json"),
                                rs.getString("sample_results_json"),
                                rs.getString("failures_json"),
                                rs.getString("status"),
                                rs.getString("gate_status"),
                                rs.getString("gate_reasons_json"),
                                rs.getString("report_markdown"),
                                rs.getString("report_json"),
                                rs.getObject("created_at", OffsetDateTime.class),
                                rs.getObject("finished_at", OffsetDateTime.class)),
                        id)
                .stream()
                .findFirst();
    }

    /** 管理页按创建时间倒序读取不可覆盖评测；MVP 数据量小，不预建汇总表。 */
    public List<EvaluationRow> list() {
        return jdbcTemplate.query(
                """
                select id, dataset_version, dataset_hash, sample_count,
                       variants_json::text, run_manifest_json::text,
                       metrics_json::text, sample_results_json::text,
                       failures_json::text, status, gate_status, gate_reasons_json::text,
                       report_markdown, report_json::text, created_at, finished_at
                  from test.evaluation_run
                 order by created_at desc, id
                """,
                (rs, rowNum) -> new EvaluationRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("dataset_version"),
                        rs.getString("dataset_hash"),
                        rs.getInt("sample_count"),
                        rs.getString("variants_json"),
                        rs.getString("run_manifest_json"),
                        rs.getString("metrics_json"),
                        rs.getString("sample_results_json"),
                        rs.getString("failures_json"),
                        rs.getString("status"),
                        rs.getString("gate_status"),
                        rs.getString("gate_reasons_json"),
                        rs.getString("report_markdown"),
                        rs.getString("report_json"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("finished_at", OffsetDateTime.class)));
    }

    /** 人工修正按 requestId 去重后追加，不改写原始样本输出或冻结报告。 */
    public int appendHumanCorrection(UUID id, String requestId, String correctionObjectJson) {
        String requestMarker = "[{\"requestId\":\"" + requestId + "\"}]";
        return jdbcTemplate.update(
                """
                update test.evaluation_run
                   set human_corrections_json =
                       coalesce(human_corrections_json, '[]'::jsonb) || (?::jsonb)
                 where id = ?
                   and not coalesce(human_corrections_json, '[]'::jsonb) @> ?::jsonb
                """,
                "[" + correctionObjectJson + "]",
                id,
                requestMarker);
    }

    public record NewEvaluation(
            UUID id,
            String requestId,
            String datasetVersion,
            String datasetHash,
            int sampleCount,
            UUID evidenceSnapshotId,
            String runManifestJson,
            String variantsJson,
            String createdBy,
            OffsetDateTime createdAt) {}

    public record CompletedEvaluation(
            UUID id,
            String sampleResultsJson,
            String metricsJson,
            String failuresJson,
            String gateStatus,
            String gateReasonsJson,
            String reportMarkdown,
            String reportJson,
            OffsetDateTime finishedAt) {}

    public record EvaluationRow(
            UUID id,
            String datasetVersion,
            String datasetHash,
            int sampleCount,
            String variantsJson,
            String runManifestJson,
            String metricsJson,
            String sampleResultsJson,
            String failuresJson,
            String status,
            String gateStatus,
            String gateReasonsJson,
            String reportMarkdown,
            String reportJson,
            OffsetDateTime createdAt,
            OffsetDateTime finishedAt) {}
}
