package com.hsmap.factverification.skill;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.hsmap.factverification.shared.ApiExceptionHandler;
import com.hsmap.factverification.skill.api.SkillVersionController;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 被测试对象：{@link SkillVersionController} 的 Skill 版本管理 HTTP 契约。
 * 测试目的：锁定草稿创建/更新/冻结/删除、所有状态正文读取、版本清单和版本卡的稳定接口。
 * 覆盖范围：固定 skillKey、幂等键、响应状态、正文 JSON 投影和删除委托。
 * 前置条件：使用 standalone MockMvc 与 Mock 服务，不连接数据库或文件系统。
 */
class SkillVersionApiTest {

    private SkillVersionService versions;
    private VersionCardService cards;
    private SkillVersionComparisonService comparisons;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        versions = Mockito.mock(SkillVersionService.class);
        cards = Mockito.mock(VersionCardService.class);
        comparisons = Mockito.mock(SkillVersionComparisonService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SkillVersionController(versions, cards, comparisons))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /**
     * 测试场景：依次创建、更新并冻结一个 DRAFT。
     * 前置条件：三个写接口都携带合法 Idempotency-Key，skillKey 使用唯一受支持值。
     * 期望结果：创建/冻结返回 201，更新返回 200，服务收到同一草稿标识。
     * 断言重点：既有版本工作流在增加内容读取和删除接口后保持兼容。
     */
    @Test
    void createsUpdatesAndFreezesDraft() throws Exception {
        UUID id = UUID.randomUUID();
        when(versions.createDraft(any(), any())).thenReturn(view(id, "DRAFT"));
        when(versions.updateDraft(eq(id), any())).thenReturn(view(id, "DRAFT"));
        when(versions.freeze(id)).thenReturn(view(id, "CANDIDATE"));

        mvc.perform(post("/api/skills/company-material-fact-check/drafts")
                        .header("Idempotency-Key", "draft-request-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeSummary\":\"修复主体歧义\"}"))
                .andExpect(status().isCreated());
        mvc.perform(
                        put("/api/skills/company-material-fact-check/drafts/{id}", id)
                                .header("Idempotency-Key", "update-request-001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"skillMarkdown":"---\\nname: company-material-fact-check\\ndescription: test\\n---\\n# Skill","references":[{"path":"references/rules.md","content":"# Rules"}],"changeSummary":"修复主体歧义"}
                                        """))
                .andExpect(status().isOk());
        mvc.perform(post("/api/skills/company-material-fact-check/drafts/{id}/freeze", id)
                        .header("Idempotency-Key", "freeze-request-001"))
                .andExpect(status().isCreated());

        verify(versions).freeze(id);
    }

    /**
     * 测试场景：管理员刷新页面后重新读取版本列表和冻结版本卡。
     * 前置条件：服务层能从持久化数据恢复一个 Candidate 及其版本卡。
     * 期望结果：两个 GET 接口均返回 200。
     * 断言重点：新增正文接口不能替代或破坏版本概要与版本卡职责。
     */
    @Test
    void listsVersionsAndReadsCard() throws Exception {
        UUID id = UUID.randomUUID();
        when(versions.list()).thenReturn(List.of(view(id, "CANDIDATE")));
        when(cards.get(id))
                .thenReturn(new VersionCardView(
                        "company-material-fact-check",
                        "v0.1.0+abcdef",
                        "CANDIDATE",
                        null,
                        "a".repeat(64),
                        "修复主体歧义",
                        null,
                        null,
                        "PENDING",
                        List.of()));

        mvc.perform(get("/api/skills/company-material-fact-check/versions")).andExpect(status().isOk());
        mvc.perform(get("/api/skills/company-material-fact-check/versions/{id}/card", id))
                .andExpect(status().isOk());
    }

    /**
     * 测试场景：管理员创建或重新打开一个 DRAFT 后读取其可编辑正文。
     * 前置条件：版本服务只允许 DRAFT 通过该投影返回内容，冻结版本仍走只读版本卡。
     * 期望结果：API 返回 SKILL.md、references 和变更说明，前端无需让管理员重新粘贴初始内容。
     * 断言重点：创建后立即可编辑是完整版本工作流的必要条件。
     */
    @Test
    void readsEditableDraftContent() throws Exception {
        UUID id = UUID.randomUUID();
        when(versions.getDraft(id))
                .thenReturn(new SkillDraftView(
                        id,
                        null,
                        "---\nname: company-material-fact-check\n---\n# Skill\n",
                        List.of(new SkillReference("references/rules.md", "# Rules\n")),
                        "初始规则"));

        mvc.perform(get("/api/skills/company-material-fact-check/drafts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skillMarkdown").value(org.hamcrest.Matchers.containsString("# Skill")))
                .andExpect(jsonPath("$.references[0].path").value("references/rules.md"));
    }

    /**
     * 测试场景：管理员直接点击冻结历史版本查看完整正文。
     * 前置条件：服务返回一个不可编辑 Candidate 的 SKILL.md 和 references 内容投影。
     * 期望结果：统一版本内容接口返回正文、references、状态和 editable=false。
     * 断言重点：查看历史版本不再错误调用只允许 DRAFT 的旧接口。
     */
    @Test
    void readsContentForFrozenVersion() throws Exception {
        UUID id = UUID.randomUUID();
        when(versions.getContent(id))
                .thenReturn(new SkillVersionContentView(
                        id,
                        null,
                        "CANDIDATE",
                        false,
                        "---\nname: company-material-fact-check\n---\n# Frozen\n",
                        List.of(new SkillReference("references/rules.md", "# Frozen Rules\n")),
                        "冻结版本"));

        mvc.perform(get("/api/skills/company-material-fact-check/versions/{id}/content", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANDIDATE"))
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.skillMarkdown").value(org.hamcrest.Matchers.containsString("# Frozen")))
                .andExpect(jsonPath("$.references[0].content")
                        .value(org.hamcrest.Matchers.containsString("Frozen Rules")));
    }

    /**
     * 测试场景：管理员在确认后删除一条 DRAFT。
     * 前置条件：请求携带合法幂等键，服务层已完成状态和父子引用保护。
     * 期望结果：接口返回 204 且准确委托目标草稿 ID。
     * 断言重点：HTTP 删除入口不接受正文参数，也不提供冻结版本删除路径。
     */
    @Test
    void deletesDraftWithIdempotencyKey() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(delete("/api/skills/company-material-fact-check/drafts/{id}", id)
                        .header("Idempotency-Key", "delete-draft-request-001"))
                .andExpect(status().isNoContent());

        verify(versions).deleteDraft(id);
    }

    /**
     * 测试场景：管理员主动点击生成按钮，再刷新页面恢复该版本比较说明。
     * 前置条件：POST 请求携带 baseVersionId 正文，GET 请求通过同名查询参数指定基础版本。
     * 期望结果：POST 委托 generate，GET 委托 get，二者都维持既有 comparison 路径。
     * 断言重点：读写语义共用稳定 URL，前端无需因页面恢复逻辑切换到新资源路径。
     */
    @Test
    void generatesAndGetsVersionComparisonAtTheSamePath() throws Exception {
        UUID baseId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        VersionComparison comparison = comparison(targetId, baseId);
        when(comparisons.generate(targetId, baseId)).thenReturn(comparison);
        when(comparisons.get(targetId, baseId)).thenReturn(comparison);

        mvc.perform(post("/api/skills/company-material-fact-check/versions/{id}/comparison", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersionId\":\"" + baseId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summaryStatus").value("COMPLETED"));
        mvc.perform(get("/api/skills/company-material-fact-check/versions/{id}/comparison", targetId)
                        .param("baseVersionId", baseId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persisted").value(true));

        verify(comparisons).generate(targetId, baseId);
        verify(comparisons).get(targetId, baseId);
    }

    private static VersionComparison comparison(UUID targetId, UUID baseId) {
        return new VersionComparison(
                targetId,
                baseId,
                "a".repeat(64),
                "b".repeat(64),
                "-旧规则\n+新规则",
                "COMPLETED",
                new GeneratedChangeSummary("强化单位归一化", List.of("金额统一"), List.of()),
                "模型生成、仅供审核参考",
                null,
                "company-qwen",
                OffsetDateTime.parse("2026-08-12T00:00:00Z"),
                true);
    }

    private static SkillVersionView view(UUID id, String status) {
        return new SkillVersionView(
                id,
                "company-material-fact-check",
                "DRAFT".equals(status) ? null : "v0.1.0+abcdef",
                null,
                status,
                "DRAFT".equals(status) ? null : "a".repeat(64),
                "修复主体歧义",
                OffsetDateTime.now(),
                "DRAFT".equals(status) ? null : OffsetDateTime.now());
    }
}
