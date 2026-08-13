package com.hsmap.factverification.skill.persistence;

import com.hsmap.factverification.release.BootstrapSkillVersion;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 管理唯一 Skill 家族的工作副本与冻结版本，不抽象为多类型资产平台。 */
@Repository
public class SkillVersionRepository {

    private final JdbcTemplate jdbcTemplate;

    public SkillVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 新建可编辑 DRAFT；冻结后的内容不会走任何 update 方法。 */
    public void insertDraft(DraftRow draft) {
        jdbcTemplate.update(
                """
                insert into test.skill_version
                  (id, skill_key, parent_version_id, status, skill_markdown, references_json,
                   allowed_tools_json, output_schema_json, change_summary, created_by, created_at)
                values (?, 'company-material-fact-check', ?, 'DRAFT', ?, ?::jsonb,
                        ?::jsonb, ?::jsonb, ?, ?, ?)
                """,
                draft.id(),
                draft.parentVersionId(),
                draft.skillMarkdown(),
                draft.referencesJson(),
                draft.allowedToolsJson(),
                draft.outputSchemaJson(),
                draft.changeSummary(),
                draft.createdBy(),
                draft.createdAt());
    }

    /** 只允许按 `status = DRAFT` 修改内容；返回 0 表示目标已冻结或不存在。 */
    public int updateDraft(UUID id, String skillMarkdown, String referencesJson, String changeSummary) {
        return jdbcTemplate.update(
                """
                update test.skill_version
                   set skill_markdown = ?, references_json = ?::jsonb, change_summary = ?
                 where id = ? and status = 'DRAFT'
                """,
                skillMarkdown,
                referencesJson,
                changeSummary,
                id);
    }

    /**
     * 判断目标版本是否已经成为其他版本的父节点。
     *
     * <p>DRAFT 一旦被克隆，就进入可追溯版本谱系；即使它本身尚未冻结，也不能删除，否则子版本的来源链会出现断点。
     *
     * @param id 待检查的父版本标识
     * @return 至少存在一个直接子版本时返回 true
     */
    public boolean hasChildren(UUID id) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from test.skill_version where parent_version_id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    /**
     * 物理删除未被引用的 DRAFT 工作副本。
     *
     * <p>SQL 同时校验状态和父子引用，避免服务层检查后发生并发冻结或克隆而误删。CANDIDATE、STABLE、ARCHIVED
     * 以及已成为父节点的草稿都会返回 0，由服务层转换为稳定业务错误。
     *
     * @param id 待删除草稿标识
     * @return 实际删除行数，成功必须恰好为 1
     */
    public int deleteDraft(UUID id) {
        return jdbcTemplate.update(
                """
                delete from test.skill_version target
                 where target.id = ?
                   and target.status = 'DRAFT'
                   and not exists (
                       select 1 from test.skill_version child
                        where child.parent_version_id = target.id
                   )
                """,
                id);
    }

    /** 初始发布只读取冻结状态和内容 hash，不载入无关的大字段。 */
    public Optional<BootstrapSkillVersion> findBootstrap(UUID id) {
        return jdbcTemplate
                .query(
                        """
                        select id, status, content_hash from test.skill_version where id = ?
                        """,
                        (rs, rowNum) -> new BootstrapSkillVersion(
                                rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("content_hash")),
                        id)
                .stream()
                .findFirst();
    }

    /** Agent 运行只读取冻结版本标识和 hash，运行时目录按 versionId 确定。 */
    public Optional<FrozenVersion> findFrozen(UUID id) {
        return jdbcTemplate
                .query(
                        """
                        select id, version, status, content_hash
                          from test.skill_version
                         where id = ? and status in ('CANDIDATE', 'STABLE', 'ARCHIVED')
                           and content_hash is not null
                        """,
                        (rs, rowNum) -> new FrozenVersion(
                                rs.getObject("id", UUID.class),
                                rs.getString("version"),
                                rs.getString("status"),
                                rs.getString("content_hash")),
                        id)
                .stream()
                .findFirst();
    }

    /** INITIALIZE 事务中仅转换内容状态，不改变任何冻结内容。 */
    public int markStable(UUID id) {
        return jdbcTemplate.update(
                """
                update test.skill_version set status = 'STABLE'
                 where id = ? and status = 'CANDIDATE' and content_hash is not null
                """,
                id);
    }

    /** 生命周期服务读取版本内容；冻结后仍只读，不提供内容更新入口。 */
    public Optional<VersionRow> findVersion(UUID id) {
        return jdbcTemplate
                .query(
                        """
                        select id, parent_version_id, version, status, skill_markdown,
                               references_json::text, allowed_tools_json::text,
                               output_schema_json::text, content_hash, change_summary,
                               version_card_json::text, registered_evaluation_id,
                               created_by, created_at, frozen_at
                          from test.skill_version where id = ?
                        """,
                        (rs, rowNum) -> mapVersion(rs),
                        id)
                .stream()
                .findFirst();
    }

    /** 页面按创建时间倒序展示单一 Skill 家族的全部版本。 */
    public List<VersionRow> listVersions() {
        return jdbcTemplate.query(
                """
                select id, parent_version_id, version, status, skill_markdown,
                       references_json::text, allowed_tools_json::text,
                       output_schema_json::text, content_hash, change_summary,
                       version_card_json::text, registered_evaluation_id,
                       created_by, created_at, frozen_at
                  from test.skill_version
                 where skill_key = 'company-material-fact-check'
                 order by created_at desc
                """,
                (rs, rowNum) -> mapVersion(rs));
    }

    /** DRAFT 冻结为 Candidate 时一次性写入版本号、hash 和冻结时间。 */
    public int freezeDraft(UUID id, String version, String contentHash, OffsetDateTime frozenAt) {
        return jdbcTemplate.update(
                """
                update test.skill_version
                   set status = 'CANDIDATE', version = ?, content_hash = ?, frozen_at = ?
                 where id = ? and status = 'DRAFT' and content_hash is null
                """,
                version,
                contentHash,
                frozenAt,
                id);
    }

    /** 注册 Candidate 时关联通过的不可变评测与版本卡。 */
    public int registerCandidate(UUID id, UUID evaluationId, String versionCardJson) {
        return jdbcTemplate.update(
                """
                update test.skill_version
                   set registered_evaluation_id = ?, version_card_json = ?::jsonb
                 where id = ? and status = 'CANDIDATE' and registered_evaluation_id is null
                """,
                evaluationId,
                versionCardJson,
                id);
    }

    /** 晋升时旧 Stable 只改变生命周期状态，冻结内容保持不变。 */
    public int markArchived(UUID id) {
        return jdbcTemplate.update(
                "update test.skill_version set status = 'ARCHIVED' where id = ? and status = 'STABLE'", id);
    }

    /** 回滚时归档版本恢复为 Stable，内容仍为原冻结快照。 */
    public int restoreStable(UUID id) {
        return jdbcTemplate.update(
                "update test.skill_version set status = 'STABLE' where id = ? and status = 'ARCHIVED'", id);
    }

    private static VersionRow mapVersion(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new VersionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("parent_version_id", UUID.class),
                rs.getString("version"),
                rs.getString("status"),
                rs.getString("skill_markdown"),
                rs.getString("references_json"),
                rs.getString("allowed_tools_json"),
                rs.getString("output_schema_json"),
                rs.getString("content_hash"),
                rs.getString("change_summary"),
                rs.getString("version_card_json"),
                rs.getObject("registered_evaluation_id", UUID.class),
                rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("frozen_at", OffsetDateTime.class));
    }

    public record DraftRow(
            UUID id,
            UUID parentVersionId,
            String skillMarkdown,
            String referencesJson,
            String allowedToolsJson,
            String outputSchemaJson,
            String changeSummary,
            String createdBy,
            OffsetDateTime createdAt) {}

    /** 一次运行需要钉死的冻结版本投影。 */
    public record FrozenVersion(UUID id, String version, String status, String contentHash) {}

    /** Skill 生命周期页面需要的完整持久化投影。 */
    public record VersionRow(
            UUID id,
            UUID parentVersionId,
            String version,
            String status,
            String skillMarkdown,
            String referencesJson,
            String allowedToolsJson,
            String outputSchemaJson,
            String contentHash,
            String changeSummary,
            String versionCardJson,
            UUID registeredEvaluationId,
            String createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime frozenAt) {}
}
