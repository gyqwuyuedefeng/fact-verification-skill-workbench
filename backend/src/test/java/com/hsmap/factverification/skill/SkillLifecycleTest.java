package com.hsmap.factverification.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.demo.DemoOperationCoordinator;
import com.hsmap.factverification.persistence.JdbcJson;
import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * 被测试对象：{@link SkillVersionService} 的版本内容读取、DRAFT 编辑/删除、冻结和克隆规则。
 * 测试目的：保证管理员能直接查看所有历史版本正文，同时只能清理尚未进入不可变谱系的草稿。
 * 覆盖范围：冻结不可变、父版本克隆、全状态内容投影、DRAFT 删除及父子引用保护。
 * 前置条件：使用 Mockito 仓储和临时 Skill 目录，不连接测试 PostgreSQL，也不写入真实版本数据。
 */
class SkillLifecycleTest {

    @TempDir
    Path tempDir;

    /**
     * 测试场景：DRAFT 冻结后再尝试修改内容。
     * 前置条件：仓储返回一个完整 DRAFT，冻结状态更新成功，后续内容更新因状态条件不命中。
     * 期望结果：生成唯一版本号和 64 位内容 hash，后续修改返回稳定业务错误。
     * 断言重点：冻结内容不可变，不能因为页面仍持有旧草稿对象而绕过状态约束。
     */
    @Test
    void freezesDraftAndRejectsLaterEdit() throws Exception {
        SkillVersionRepository repository = Mockito.mock(SkillVersionRepository.class);
        UUID id = UUID.randomUUID();
        SkillVersionRepository.VersionRow draft = draft(id, null);
        when(repository.findVersion(id)).thenReturn(Optional.of(draft));
        when(repository.freezeDraft(eq(id), anyString(), anyString(), any())).thenReturn(1);
        when(repository.updateDraft(eq(id), anyString(), anyString(), anyString()))
                .thenReturn(0);
        SkillVersionService service = service(repository);

        SkillVersionView frozen = service.freeze(id);

        assertThat(frozen.status()).isEqualTo("CANDIDATE");
        assertThat(frozen.version()).startsWith("v0.1.0+");
        assertThat(frozen.contentHash()).hasSize(64);
        assertThatThrownBy(() -> service.updateDraft(id, new SkillDraftContent("# changed", List.of(), "不应允许")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("SKILL_DRAFT_NOT_EDITABLE");
    }

    /**
     * 测试场景：从一个已有冻结版本克隆新的 DRAFT。
     * 前置条件：父版本正文和 references 已持久化且可读取。
     * 期望结果：服务只插入新草稿并记录 parentVersionId，不修改父版本内容。
     * 断言重点：版本谱系和冻结不可变边界同时保留。
     */
    @Test
    void clonesParentIntoNewDraftWithoutChangingParent() throws Exception {
        SkillVersionRepository repository = Mockito.mock(SkillVersionRepository.class);
        UUID parentId = UUID.randomUUID();
        when(repository.findVersion(parentId)).thenReturn(Optional.of(draft(parentId, null)));
        SkillVersionService service = service(repository);

        SkillVersionView cloned = service.createDraft(parentId, "修复主体歧义");

        assertThat(cloned.parentVersionId()).isEqualTo(parentId);
        assertThat(cloned.status()).isEqualTo("DRAFT");
        verify(repository).insertDraft(any());
        Mockito.verify(repository, Mockito.never()).updateDraft(eq(parentId), anyString(), anyString(), anyString());
    }

    /**
     * 测试场景：管理员点击 DRAFT 与冻结历史版本查看完整正文。
     * 前置条件：仓储分别返回 DRAFT 和 CANDIDATE，二者都保存相同结构的 SKILL.md 与 references。
     * 期望结果：统一内容投影原样返回正文，且仅 DRAFT 标记为 editable；旧草稿接口仍拒绝冻结版本。
     * 断言重点：查看冻结历史不再依赖先克隆 DRAFT，同时不会把冻结内容误暴露为可编辑对象。
     */
    @Test
    void exposesEveryVersionContentButOnlyDraftIsEditable() throws Exception {
        SkillVersionRepository repository = Mockito.mock(SkillVersionRepository.class);
        UUID draftId = UUID.randomUUID();
        UUID frozenId = UUID.randomUUID();
        SkillVersionRepository.VersionRow editable = draft(draftId, null);
        SkillVersionRepository.VersionRow frozen = new SkillVersionRepository.VersionRow(
                frozenId,
                null,
                "v0.1.0+abcdef",
                "CANDIDATE",
                editable.skillMarkdown(),
                editable.referencesJson(),
                editable.allowedToolsJson(),
                editable.outputSchemaJson(),
                "a".repeat(64),
                editable.changeSummary(),
                null,
                null,
                editable.createdBy(),
                editable.createdAt(),
                OffsetDateTime.now());
        when(repository.findVersion(draftId)).thenReturn(Optional.of(editable));
        when(repository.findVersion(frozenId)).thenReturn(Optional.of(frozen));
        SkillVersionService service = service(repository);

        SkillVersionContentView draftView = service.getContent(draftId);
        SkillVersionContentView frozenView = service.getContent(frozenId);

        assertThat(draftView.editable()).isTrue();
        assertThat(draftView.skillMarkdown()).contains("# Test");
        assertThat(draftView.references()).singleElement().satisfies(reference ->
                assertThat(reference.path()).isEqualTo("references/rules.md"));
        assertThat(frozenView.editable()).isFalse();
        assertThat(frozenView.skillMarkdown()).contains("# Test");
        assertThatThrownBy(() -> service.getDraft(frozenId))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("SKILL_DRAFT_NOT_EDITABLE");
    }

    /**
     * 测试场景：管理员删除普通 DRAFT、已被子版本引用的 DRAFT 和冻结版本。
     * 前置条件：三种目标均真实存在；只有普通 DRAFT 没有子版本引用。
     * 期望结果：普通 DRAFT 被物理删除，其余两种请求返回稳定业务错误且不调用删除 SQL。
     * 断言重点：删除能力只用于清理工作副本，不能破坏版本谱系或不可变审核历史。
     */
    @Test
    void deletesOnlyUnreferencedDraft() throws Exception {
        SkillVersionRepository repository = Mockito.mock(SkillVersionRepository.class);
        UUID draftId = UUID.randomUUID();
        UUID referencedDraftId = UUID.randomUUID();
        UUID frozenId = UUID.randomUUID();
        SkillVersionRepository.VersionRow editable = draft(draftId, null);
        SkillVersionRepository.VersionRow referenced = draft(referencedDraftId, null);
        SkillVersionRepository.VersionRow frozen = new SkillVersionRepository.VersionRow(
                frozenId,
                null,
                "v0.1.0+abcdef",
                "CANDIDATE",
                editable.skillMarkdown(),
                editable.referencesJson(),
                editable.allowedToolsJson(),
                editable.outputSchemaJson(),
                "a".repeat(64),
                editable.changeSummary(),
                null,
                null,
                editable.createdBy(),
                editable.createdAt(),
                OffsetDateTime.now());
        when(repository.findVersion(draftId)).thenReturn(Optional.of(editable));
        when(repository.findVersion(referencedDraftId)).thenReturn(Optional.of(referenced));
        when(repository.findVersion(frozenId)).thenReturn(Optional.of(frozen));
        when(repository.hasChildren(draftId)).thenReturn(false);
        when(repository.hasChildren(referencedDraftId)).thenReturn(true);
        when(repository.deleteDraft(draftId)).thenReturn(1);
        SkillVersionService service = service(repository);

        service.deleteDraft(draftId);

        verify(repository).deleteDraft(draftId);
        assertThatThrownBy(() -> service.deleteDraft(referencedDraftId))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("SKILL_DRAFT_HAS_CHILDREN");
        assertThatThrownBy(() -> service.deleteDraft(frozenId))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("SKILL_DRAFT_NOT_DELETABLE");
        Mockito.verify(repository, Mockito.never()).deleteDraft(referencedDraftId);
        Mockito.verify(repository, Mockito.never()).deleteDraft(frozenId);
    }

    /**
     * 测试场景：快照导入/reset 一类管理操作已持有写锁，管理员同时冻结 DRAFT。
     * 前置条件：DRAFT 内容完整且数据库冻结更新可成功，写锁由闩锁稳定占有。
     * 期望结果：写锁释放前不产生 snapshot/runtime 版本目录，释放后冻结正常完成。
     * 断言重点：Skill 两个物理目录与状态更新的完整周期必须参与文件生产读锁。
     */
    @Test
    void blocksFreezeFileLifecycleWhileManagementWriteLockIsHeld() throws Exception {
        SkillVersionRepository repository = Mockito.mock(SkillVersionRepository.class);
        UUID id = UUID.randomUUID();
        when(repository.findVersion(id)).thenReturn(Optional.of(draft(id, null)));
        when(repository.freezeDraft(eq(id), anyString(), anyString(), any())).thenReturn(1);
        DemoOperationCoordinator coordinator = new DemoOperationCoordinator();
        SkillVersionService service = service(repository, coordinator);
        CountDownLatch managementEntered = new CountDownLatch(1);
        CountDownLatch releaseManagement = new CountDownLatch(1);
        CountDownLatch freezeStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> management = executor.submit(() -> coordinator.exclusively(() -> {
                managementEntered.countDown();
                await(releaseManagement);
            }));
            assertThat(managementEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> freezing = executor.submit(() -> {
                freezeStarted.countDown();
                service.freeze(id);
            });
            assertThat(freezeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);

            assertThat(tempDir.resolve("skill-snapshots/" + id)).doesNotExist();
            assertThat(tempDir.resolve("skill-runtime/" + id)).doesNotExist();
            releaseManagement.countDown();
            management.get(5, TimeUnit.SECONDS);
            freezing.get(5, TimeUnit.SECONDS);
            assertThat(tempDir.resolve("skill-snapshots/" + id)).isDirectory();
            assertThat(tempDir.resolve("skill-runtime/" + id)).isDirectory();
        } finally {
            releaseManagement.countDown();
            executor.shutdownNow();
        }
    }

    private SkillVersionService service(SkillVersionRepository repository) throws Exception {
        return service(repository, new DemoOperationCoordinator());
    }

    /** 为文件生产并发测试显式注入与管理操作共享的协调器。 */
    private SkillVersionService service(
            SkillVersionRepository repository, DemoOperationCoordinator coordinator) throws Exception {
        Path source = tempDir.resolve("source");
        Files.createDirectories(source.resolve("references"));
        Files.writeString(
                source.resolve("SKILL.md"), "---\nname: company-material-fact-check\ndescription: test\n---\n# Test\n");
        Files.writeString(source.resolve("references/rules.md"), "# Rules\n");
        WorkbenchProperties properties = new WorkbenchProperties(
                new WorkbenchProperties.DatabaseBoundary("db", "test", false),
                tempDir,
                tempDir.resolve("manifest.json"),
                source,
                new WorkbenchProperties.Model("url", "/v1/chat/completions", "model", "key"),
                URI.create("http://127.0.0.1:18081/mcp"));
        return new SkillVersionService(
                repository,
                new FrozenSkillStorage(tempDir.resolve("skill-snapshots"), tempDir.resolve("skill-runtime")),
                properties,
                new ObjectMapper(),
                new JdbcJson(new ObjectMapper()),
                coordinator);
    }

    /** 线程内等待管理锁释放，中断时保留标记并转换为明确失败。 */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待管理锁释放超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待管理锁释放被中断", exception);
        }
    }

    private static SkillVersionRepository.VersionRow draft(UUID id, UUID parentId) {
        return new SkillVersionRepository.VersionRow(
                id,
                parentId,
                null,
                "DRAFT",
                "---\nname: company-material-fact-check\ndescription: test\n---\n# Test\n",
                "[{\"path\":\"references/rules.md\",\"content\":\"# Rules\\n\"}]",
                "[\"resolve_company\",\"get_company_profile\",\"get_company_financials\",\"get_company_intellectual_property\",\"get_company_risks\",\"get_company_relationships\"]",
                "{\"type\":\"object\"}",
                null,
                "修复已知问题",
                null,
                null,
                "tester",
                OffsetDateTime.now(),
                null);
    }
}
