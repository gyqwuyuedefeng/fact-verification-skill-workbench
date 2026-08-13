package com.hsmap.factverification.evidence.persistence;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 保存工具请求的不可变证据快照，使基线与各 Skill 变体复用同一响应。 */
@Repository
public class EvidenceSnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    public EvidenceSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 首次取数后追加一条快照；并发重复由唯一键收敛，调用方随后读取已提交记录。 */
    public boolean append(SnapshotRow row) {
        try {
            jdbcTemplate.update(
                    """
                    insert into test.evidence_snapshot
                      (id, snapshot_id, owner_type, owner_id, tool_name, canonical_arguments,
                       arguments_hash, fetched_at, response_json, error_code, error_summary,
                       response_hash, created_at)
                    values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?, ?, ?)
                    """,
                    row.id(),
                    row.snapshotId(),
                    row.ownerType(),
                    row.ownerId(),
                    row.toolName(),
                    row.canonicalArgumentsJson(),
                    row.argumentsHash(),
                    row.fetchedAt(),
                    row.responseJson(),
                    row.errorCode(),
                    row.errorSummary(),
                    row.responseHash(),
                    row.createdAt());
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    /** 唯一规范化请求只能读取一个冻结结果。 */
    public Optional<SnapshotView> find(UUID snapshotId, String toolName, String argumentsHash) {
        return jdbcTemplate
                .query(
                        """
                        select response_json::text, error_code, error_summary, response_hash, fetched_at
                          from test.evidence_snapshot
                         where snapshot_id = ? and tool_name = ? and arguments_hash = ?
                        """,
                        (rs, rowNum) -> new SnapshotView(
                                rs.getString("response_json"),
                                rs.getString("error_code"),
                                rs.getString("error_summary"),
                                rs.getString("response_hash"),
                                rs.getObject("fetched_at", OffsetDateTime.class)),
                        snapshotId,
                        toolName,
                        argumentsHash)
                .stream()
                .findFirst();
    }

    public record SnapshotRow(
            UUID id,
            UUID snapshotId,
            String ownerType,
            UUID ownerId,
            String toolName,
            String canonicalArgumentsJson,
            String argumentsHash,
            OffsetDateTime fetchedAt,
            String responseJson,
            String errorCode,
            String errorSummary,
            String responseHash,
            OffsetDateTime createdAt) {}

    public record SnapshotView(
            String responseJson,
            String errorCode,
            String errorSummary,
            String responseHash,
            OffsetDateTime fetchedAt) {}
}
