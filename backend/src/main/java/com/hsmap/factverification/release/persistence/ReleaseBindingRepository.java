package com.hsmap.factverification.release.persistence;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 以只追加事件记录当前发布绑定；最新 revision 的 state_after 即当前状态。 */
@Repository
public class ReleaseBindingRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReleaseBindingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 是否已经存在首条发布事件，用于拒绝重复 INITIALIZE。 */
    public boolean exists(String skillKey) {
        Boolean exists = jdbcTemplate.queryForObject(
                "select exists(select 1 from test.release_binding where skill_key = ?)", Boolean.class, skillKey);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 锁定最新事件供后续状态转换使用。
     *
     * <p>MVP 只有单 Skill 家族，因此不新增单独锁表；当没有历史事件时由 revision 唯一键处理初始化竞争。
     */
    public Optional<ReleaseState> findLatestForUpdate(String skillKey) {
        return jdbcTemplate
                .query(
                        """
                        select revision, action, stable_version_id, candidate_version_id,
                               previous_stable_version_id, shadow_enabled, evaluation_run_id,
                               state_after::text, reason, operator, created_at
                          from test.release_binding
                         where skill_key = ? order by revision desc limit 1 for update
                        """,
                        (rs, rowNum) -> new ReleaseState(
                                rs.getLong("revision"),
                                rs.getString("action"),
                                rs.getObject("stable_version_id", UUID.class),
                                rs.getObject("candidate_version_id", UUID.class),
                                rs.getObject("previous_stable_version_id", UUID.class),
                                rs.getBoolean("shadow_enabled"),
                                rs.getObject("evaluation_run_id", UUID.class),
                                rs.getString("state_after"),
                                rs.getString("reason"),
                                rs.getString("operator"),
                                rs.getObject("created_at", OffsetDateTime.class)),
                        skillKey)
                .stream()
                .findFirst();
    }

    /** 新任务启动时读取最新发布状态并立即钉死版本，不持有数据库锁。 */
    public Optional<ReleaseState> findLatest(String skillKey) {
        return jdbcTemplate
                .query(
                        """
                        select revision, action, stable_version_id, candidate_version_id,
                               previous_stable_version_id, shadow_enabled, evaluation_run_id,
                               state_after::text, reason, operator, created_at
                          from test.release_binding
                         where skill_key = ? order by revision desc limit 1
                        """,
                        (rs, rowNum) -> new ReleaseState(
                                rs.getLong("revision"),
                                rs.getString("action"),
                                rs.getObject("stable_version_id", UUID.class),
                                rs.getObject("candidate_version_id", UUID.class),
                                rs.getObject("previous_stable_version_id", UUID.class),
                                rs.getBoolean("shadow_enabled"),
                                rs.getObject("evaluation_run_id", UUID.class),
                                rs.getString("state_after"),
                                rs.getString("reason"),
                                rs.getString("operator"),
                                rs.getObject("created_at", OffsetDateTime.class)),
                        skillKey)
                .stream()
                .findFirst();
    }

    /** 追加历史倒序展示，发布事件从不 update/delete。 */
    public java.util.List<ReleaseState> listHistory(String skillKey) {
        return jdbcTemplate.query(
                """
                select revision, action, stable_version_id, candidate_version_id,
                       previous_stable_version_id, shadow_enabled, evaluation_run_id,
                       state_after::text, reason, operator, created_at
                  from test.release_binding
                 where skill_key = ? order by revision desc
                """,
                (rs, rowNum) -> new ReleaseState(
                        rs.getLong("revision"),
                        rs.getString("action"),
                        rs.getObject("stable_version_id", UUID.class),
                        rs.getObject("candidate_version_id", UUID.class),
                        rs.getObject("previous_stable_version_id", UUID.class),
                        rs.getBoolean("shadow_enabled"),
                        rs.getObject("evaluation_run_id", UUID.class),
                        rs.getString("state_after"),
                        rs.getString("reason"),
                        rs.getString("operator"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                skillKey);
    }

    /** 追加一条审计完整的发布事件；数据库唯一键保证 revision 不重复。 */
    public void append(ReleaseEvent event) {
        jdbcTemplate.update(
                """
                insert into test.release_binding
                  (id, skill_key, revision, action, stable_version_id, candidate_version_id,
                   previous_stable_version_id, shadow_enabled, evaluation_run_id,
                   state_before, state_after, reason, operator, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                """,
                event.id(),
                event.skillKey(),
                event.revision(),
                event.action(),
                event.stableVersionId(),
                event.candidateVersionId(),
                event.previousStableVersionId(),
                event.shadowEnabled(),
                event.evaluationRunId(),
                event.stateBeforeJson(),
                event.stateAfterJson(),
                event.reason(),
                event.operator(),
                event.createdAt());
    }

    /** 空历史从 revision 1 开始，其余严格加一。 */
    public static long nextRevision(Long currentRevision) {
        return currentRevision == null ? 1L : Math.addExact(currentRevision, 1L);
    }

    public record ReleaseState(
            long revision,
            String action,
            UUID stableVersionId,
            UUID candidateVersionId,
            UUID previousStableVersionId,
            boolean shadowEnabled,
            UUID evaluationRunId,
            String stateAfterJson,
            String reason,
            String operator,
            OffsetDateTime createdAt) {}

    public record ReleaseEvent(
            UUID id,
            String skillKey,
            long revision,
            String action,
            UUID stableVersionId,
            UUID candidateVersionId,
            UUID previousStableVersionId,
            boolean shadowEnabled,
            UUID evaluationRunId,
            String stateBeforeJson,
            String stateAfterJson,
            String reason,
            String operator,
            OffsetDateTime createdAt) {}
}
