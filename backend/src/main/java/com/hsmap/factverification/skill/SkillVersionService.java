package com.hsmap.factverification.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.persistence.JdbcJson;
import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理唯一 `company-material-fact-check` Skill 家族的 DRAFT 与冻结 Candidate。 */
@Service
public class SkillVersionService {

    public static final String SKILL_KEY = "company-material-fact-check";
    private static final String OPERATOR = "single-reviewer";
    private static final List<String> ALLOWED_TOOLS = List.of(
            "resolve_company",
            "get_company_profile",
            "get_company_financials",
            "get_company_intellectual_property",
            "get_company_risks",
            "get_company_relationships");

    private final SkillVersionRepository versions;
    private final FrozenSkillStorage storage;
    private final WorkbenchProperties properties;
    private final ObjectMapper objectMapper;
    private final JdbcJson jdbcJson;

    /** Spring 生产装配入口；显式标记避免包内测试构造器参与运行时选择。 */
    @Autowired
    public SkillVersionService(
            SkillVersionRepository versions,
            WorkbenchProperties properties,
            ObjectMapper objectMapper,
            JdbcJson jdbcJson) {
        this(
                versions,
                new FrozenSkillStorage(
                        properties.storageRoot().resolve("skill-snapshots"),
                        properties.storageRoot().resolve("skill-runtime")),
                properties,
                objectMapper,
                jdbcJson);
    }

    /** 测试可注入隔离目录，生产仍使用 workbench storageRoot。 */
    SkillVersionService(
            SkillVersionRepository versions,
            FrozenSkillStorage storage,
            WorkbenchProperties properties,
            ObjectMapper objectMapper,
            JdbcJson jdbcJson) {
        this.versions = versions;
        this.storage = storage;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jdbcJson = jdbcJson;
    }

    /** 从当前仓库 Skill 或指定父版本克隆出一个全新 DRAFT。 */
    @Transactional
    public SkillVersionView createDraft(UUID parentVersionId, String changeSummary) {
        SkillDraftContent content =
                parentVersionId == null ? readSourceSkill(changeSummary) : cloneParent(parentVersionId, changeSummary);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        versions.insertDraft(new SkillVersionRepository.DraftRow(
                id,
                parentVersionId,
                content.skillMarkdown(),
                jdbcJson.write(content.references()),
                jdbcJson.write(ALLOWED_TOOLS),
                outputSchemaJson(),
                content.changeSummary(),
                OPERATOR,
                now));
        return new SkillVersionView(
                id, SKILL_KEY, null, parentVersionId, "DRAFT", null, content.changeSummary(), now, null);
    }

    /** 只允许修改数据库仍为 DRAFT 的版本。 */
    @Transactional
    public SkillVersionView updateDraft(UUID id, SkillDraftContent content) {
        validateDraft(content);
        int changed = versions.updateDraft(
                id, content.skillMarkdown(), jdbcJson.write(content.references()), content.changeSummary());
        if (changed != 1) {
            throw new ServiceException("SKILL_DRAFT_NOT_EDITABLE", "只有 DRAFT 可以修改");
        }
        SkillVersionRepository.VersionRow existing = find(id);
        return new SkillVersionView(
                id,
                SKILL_KEY,
                null,
                existing.parentVersionId(),
                "DRAFT",
                null,
                content.changeSummary(),
                existing.createdAt(),
                null);
    }

    /** 将 DRAFT 文件化、hash、物理只读，并原子转换为 Candidate。 */
    @Transactional
    public SkillVersionView freeze(UUID id) {
        SkillVersionRepository.VersionRow draft = find(id);
        if (!"DRAFT".equals(draft.status())) {
            throw new ServiceException("SKILL_DRAFT_NOT_EDITABLE", "只有 DRAFT 可以冻结");
        }
        if (draft.changeSummary() == null || draft.changeSummary().isBlank()) {
            throw new ServiceException("SKILL_CHANGE_SUMMARY_REQUIRED", "冻结前必须填写变更说明");
        }
        FrozenSkillSnapshot snapshot =
                storage.freeze(id, draft.skillMarkdown(), readReferences(draft.referencesJson()));
        String version = "v0.1.0+" + snapshot.contentHash().substring(0, 12);
        OffsetDateTime frozenAt = OffsetDateTime.now(ZoneOffset.UTC);
        if (versions.freezeDraft(id, version, snapshot.contentHash(), frozenAt) != 1) {
            throw new ServiceException("SKILL_FREEZE_CONFLICT", "Skill 已被其他请求冻结");
        }
        return new SkillVersionView(
                id,
                SKILL_KEY,
                version,
                draft.parentVersionId(),
                "CANDIDATE",
                snapshot.contentHash(),
                draft.changeSummary(),
                draft.createdAt(),
                frozenAt);
    }

    public List<SkillVersionView> list() {
        return versions.listVersions().stream().map(SkillVersionService::view).toList();
    }

    /**
     * 读取管理员可继续编辑的 DRAFT。
     *
     * <p>该方法保留给既有草稿接口兼容使用；页面查看任意状态正文应调用 {@link #getContent(UUID)}，并以其中的 editable
     * 字段控制编辑器。服务端更新时仍会再次检查数据库状态，不能仅依赖前端只读属性。
     *
     * @param id 草稿版本标识
     * @return 可编辑草稿内容
     * @throws ServiceException 目标不存在或已经冻结时抛出稳定业务错误
     */
    public SkillDraftView getDraft(UUID id) {
        SkillVersionContentView content = getContent(id);
        if (!content.editable()) {
            throw new ServiceException("SKILL_DRAFT_NOT_EDITABLE", "只有 DRAFT 可以在编辑器中打开");
        }
        return new SkillDraftView(
                content.id(),
                content.parentVersionId(),
                content.skillMarkdown(),
                content.references(),
                content.changeSummary());
    }

    /**
     * 返回任意生命周期状态的完整版本内容，供版本历史查看和差异审核使用。
     *
     * <p>冻结版本仍从数据库中已经持久化的不可变内容读取，不从当前可编辑源目录回填，因而不会被后续模板修改污染。
     *
     * @param id 需要查看的版本标识
     * @return 包含正文、references、状态和可编辑性的统一投影
     * @throws ServiceException 版本不存在或 references 快照损坏时抛出稳定业务错误
     */
    public SkillVersionContentView getContent(UUID id) {
        SkillVersionRepository.VersionRow row = find(id);
        return new SkillVersionContentView(
                row.id(),
                row.parentVersionId(),
                row.status(),
                "DRAFT".equals(row.status()),
                row.skillMarkdown(),
                readReferences(row.referencesJson()),
                row.changeSummary());
    }

    /**
     * 删除尚未形成版本谱系引用的工作草稿。
     *
     * <p>冻结版本承载评测、发布与回滚审计，不允许删除；已经成为其他版本父节点的 DRAFT 也必须保留。仓储删除 SQL 会再次检查状态和父子引用，
     * 以覆盖检查完成后发生并发冻结或克隆的竞争窗口。
     *
     * @param id 待删除草稿标识
     * @throws ServiceException 目标不存在、不是 DRAFT、存在子版本或并发状态变化时抛出稳定业务错误
     */
    @Transactional
    public void deleteDraft(UUID id) {
        SkillVersionRepository.VersionRow row = find(id);
        if (!"DRAFT".equals(row.status())) {
            throw new ServiceException("SKILL_DRAFT_NOT_DELETABLE", "只有 DRAFT 可以删除");
        }
        if (versions.hasChildren(id)) {
            throw new ServiceException("SKILL_DRAFT_HAS_CHILDREN", "该 DRAFT 已成为其他版本的父版本，不能删除");
        }
        if (versions.deleteDraft(id) != 1) {
            throw new ServiceException("SKILL_DRAFT_DELETE_CONFLICT", "DRAFT 状态或版本引用已变化，请刷新后重试");
        }
    }

    public SkillVersionRepository.VersionRow get(UUID id) {
        return find(id);
    }

    private SkillDraftContent cloneParent(UUID parentId, String changeSummary) {
        SkillVersionRepository.VersionRow parent = find(parentId);
        return new SkillDraftContent(parent.skillMarkdown(), readReferences(parent.referencesJson()), changeSummary);
    }

    private SkillDraftContent readSourceSkill(String changeSummary) {
        Path sourceRoot = properties.skillSourceRoot().toAbsolutePath().normalize();
        try {
            String markdown = Files.readString(sourceRoot.resolve("SKILL.md"), StandardCharsets.UTF_8);
            List<SkillReference> references = new ArrayList<>();
            Path referencesRoot = sourceRoot.resolve("references");
            if (Files.isDirectory(referencesRoot)) {
                try (var files = Files.walk(referencesRoot)) {
                    for (Path file : files.filter(Files::isRegularFile)
                            .filter(path ->
                                    !".gitkeep".equals(path.getFileName().toString()))
                            .sorted()
                            .toList()) {
                        references.add(new SkillReference(
                                sourceRoot.relativize(file).toString().replace('\\', '/'),
                                Files.readString(file, StandardCharsets.UTF_8)));
                    }
                }
            }
            return new SkillDraftContent(markdown, List.copyOf(references), changeSummary);
        } catch (IOException exception) {
            throw new ServiceException("SKILL_SOURCE_READ_FAILED", "初始 Skill 源文件无法读取");
        }
    }

    private List<SkillReference> readReferences(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<SkillReference>>() {});
        } catch (JsonProcessingException exception) {
            throw new ServiceException("SKILL_REFERENCES_INVALID", "Skill 参考文件格式无效");
        }
    }

    private String outputSchemaJson() {
        try (InputStream input = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("schemas/verification-result.schema.json")) {
            if (input == null) {
                throw new ServiceException("OUTPUT_SCHEMA_MISSING", "统一输出 schema 不存在");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ServiceException("OUTPUT_SCHEMA_INVALID", "统一输出 schema 无法读取");
        }
    }

    private static void validateDraft(SkillDraftContent content) {
        if (content == null
                || content.skillMarkdown() == null
                || content.skillMarkdown().isBlank()
                || content.references() == null
                || content.changeSummary() == null
                || content.changeSummary().isBlank()
                || content.changeSummary().length() > 2000) {
            throw new ServiceException("SKILL_DRAFT_INVALID", "Skill 内容、参考文件和变更说明不能为空");
        }
    }

    private SkillVersionRepository.VersionRow find(UUID id) {
        return versions.findVersion(id)
                .orElseThrow(() -> new ServiceException("SKILL_VERSION_NOT_FOUND", "Skill 版本不存在"));
    }

    private static SkillVersionView view(SkillVersionRepository.VersionRow row) {
        return new SkillVersionView(
                row.id(),
                SKILL_KEY,
                row.version(),
                row.parentVersionId(),
                row.status(),
                row.contentHash(),
                row.changeSummary(),
                row.createdAt(),
                row.frozenAt());
    }
}
