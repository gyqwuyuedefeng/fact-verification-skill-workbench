package com.hsmap.factverification.task.persistence;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 保存真实材料任务；仅任务状态与解析结果允许在生命周期内更新。 */
@Repository
public class VerificationTaskRepository {

    private final JdbcTemplate jdbcTemplate;

    public VerificationTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按幂等键写入一次上传任务，重复 requestId 由数据库唯一键拒绝。 */
    public void insert(NewTask task) {
        jdbcTemplate.update(
                """
                insert into test.verification_task
                  (id, request_id, original_file_name, media_type, file_size, file_hash,
                   upload_path, input_type, status, shadow_requested, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, 'FILE', 'UPLOADED', ?, ?, ?)
                """,
                task.id(),
                task.requestId(),
                task.originalFileName(),
                task.mediaType(),
                task.fileSize(),
                task.fileHash(),
                task.uploadPath(),
                task.shadowRequested(),
                task.createdAt(),
                task.createdAt());
    }

    /** 查询页面与运行编排当前需要的任务投影。 */
    public Optional<TaskState> findById(UUID id) {
        return jdbcTemplate
                .query(
                        """
                        select id, request_id, user_message, input_type, original_file_name, file_hash, status,
                               shadow_requested, evidence_snapshot_id,
                               document_snapshot::text, document_snapshot_hash,
                               error_code, created_at
                          from test.verification_task where id = ?
                        """,
                        (rs, rowNum) -> new TaskState(
                                rs.getObject("id", UUID.class),
                                rs.getString("request_id"),
                                rs.getString("user_message"),
                                rs.getString("input_type"),
                                rs.getString("original_file_name"),
                                rs.getString("file_hash"),
                                rs.getString("status"),
                                rs.getBoolean("shadow_requested"),
                                rs.getObject("evidence_snapshot_id", UUID.class),
                                rs.getString("document_snapshot"),
                                rs.getString("document_snapshot_hash"),
                                rs.getString("error_code"),
                                rs.getObject("created_at", OffsetDateTime.class)),
                        id)
                .stream()
                .findFirst();
    }

    /** 创建接口幂等命中时返回既有任务。 */
    public Optional<TaskState> findByRequestId(String requestId) {
        return jdbcTemplate
                .query(
                        """
                        select id, request_id, user_message, input_type, original_file_name, file_hash, status,
                               shadow_requested, evidence_snapshot_id,
                               document_snapshot::text, document_snapshot_hash,
                               error_code, created_at
                          from test.verification_task where request_id = ?
                        """,
                        (rs, rowNum) -> new TaskState(
                                rs.getObject("id", UUID.class),
                                rs.getString("request_id"),
                                rs.getString("user_message"),
                                rs.getString("input_type"),
                                rs.getString("original_file_name"),
                                rs.getString("file_hash"),
                                rs.getString("status"),
                                rs.getBoolean("shadow_requested"),
                                rs.getObject("evidence_snapshot_id", UUID.class),
                                rs.getString("document_snapshot"),
                                rs.getString("document_snapshot_hash"),
                                rs.getString("error_code"),
                                rs.getObject("created_at", OffsetDateTime.class)),
                        requestId)
                .stream()
                .findFirst();
    }

    /** 上传后替换任务槽的占位文件元数据，只允许尚未解析的任务执行一次。 */
    public int attachMaterial(
            UUID id,
            String originalFileName,
            String mediaType,
            long fileSize,
            String fileHash,
            String uploadPath,
            String userMessage,
            String inputType,
            OffsetDateTime updatedAt) {
        return jdbcTemplate.update(
                """
                update test.verification_task
                   set original_file_name = ?, media_type = ?, file_size = ?, file_hash = ?,
                       upload_path = ?, user_message = ?, input_type = ?, status = 'PARSING', updated_at = ?
                 where id = ? and status = 'UPLOADED' and parser_version is null
                """,
                originalFileName,
                mediaType,
                fileSize,
                fileHash,
                uploadPath,
                userMessage,
                inputType,
                updatedAt,
                id);
    }

    /** 任务状态只允许从调用方声明的当前状态转换，避免并发覆盖。 */
    public int transition(UUID id, String fromStatus, String toStatus, OffsetDateTime updatedAt) {
        return jdbcTemplate.update(
                "update test.verification_task set status = ?, updated_at = ? where id = ? and status = ?",
                toStatus,
                updatedAt,
                id,
                fromStatus);
    }

    /** 在实际创建 SHADOW 前记录本任务参加了灰度，便于重启后页面恢复。 */
    public int markShadowRequested(UUID id, OffsetDateTime updatedAt) {
        return jdbcTemplate.update(
                """
                update test.verification_task
                   set shadow_requested = true, updated_at = ?
                 where id = ? and status = 'READY' and shadow_requested = false
                """,
                updatedAt,
                id);
    }

    /** 解析成功后一次性固定文档快照，READY 之后业务服务不得再次调用。 */
    public int markReady(
            UUID id,
            String parserVersion,
            String snapshotJson,
            String snapshotHash,
            UUID evidenceSnapshotId,
            OffsetDateTime updatedAt) {
        return jdbcTemplate.update(
                """
                update test.verification_task
                   set parser_version = ?, document_snapshot = ?::jsonb,
                       document_snapshot_hash = ?, evidence_snapshot_id = ?,
                       status = 'READY', updated_at = ?
                 where id = ? and status in ('UPLOADED', 'PARSING')
                """,
                parserVersion,
                snapshotJson,
                snapshotHash,
                evidenceSnapshotId,
                updatedAt,
                id);
    }

    /** 新任务写入所需的最小字段集合。 */
    public record NewTask(
            UUID id,
            String requestId,
            String originalFileName,
            String mediaType,
            long fileSize,
            String fileHash,
            String uploadPath,
            boolean shadowRequested,
            OffsetDateTime createdAt) {}

    /** 任务查询投影不回传上传路径或完整错误堆栈。 */
    public record TaskState(
            UUID id,
            String requestId,
            String userMessage,
            String inputType,
            String originalFileName,
            String fileHash,
            String status,
            boolean shadowRequested,
            UUID evidenceSnapshotId,
            String documentSnapshotJson,
            String documentSnapshotHash,
            String errorCode,
            OffsetDateTime createdAt) {

        /** 兼容只关心既有文件任务的单元测试；新测试应显式传入输入类型。 */
        public TaskState(
                UUID id,
                String requestId,
                String originalFileName,
                String fileHash,
                String status,
                boolean shadowRequested,
                UUID evidenceSnapshotId,
                String documentSnapshotJson,
                String documentSnapshotHash,
                String errorCode,
                OffsetDateTime createdAt) {
            this(
                    id,
                    requestId,
                    null,
                    "FILE",
                    originalFileName,
                    fileHash,
                    status,
                    shadowRequested,
                    evidenceSnapshotId,
                    documentSnapshotJson,
                    documentSnapshotHash,
                    errorCode,
                    createdAt);
        }
    }
}
