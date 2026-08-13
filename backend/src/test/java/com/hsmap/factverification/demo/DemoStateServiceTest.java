package com.hsmap.factverification.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hsmap.factverification.shared.ServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 被测试对象：DemoStateService 的比赛演示状态检查与清空编排。
 * 测试目的：确保仅在没有活动评测或核验时清理固定七张业务表，并让运行目录与数据库结果保持可恢复的一致性。
 * 覆盖范围：活动任务保护、确认短语、数据库失败回滚和成功后的空状态投影。
 * 前置条件：数据库访问通过仓储 Mock 隔离；文件场景使用 JUnit 临时目录模拟受管运行目录。
 */
class DemoStateServiceTest {

    @TempDir
    Path storageRoot;

    /**
     * 测试场景：存在 PENDING 或 RUNNING 的评测。
     * 前置条件：仓储报告活动评测，且尚未执行任何目录交换或数据库删除。
     * 期望结果：服务拒绝清空并给出统一的活动任务说明。
     * 断言重点：clearAll 不得被调用，避免中途删除仍被后台线程引用的数据。
     */
    @Test
    void rejectsResetWhenEvaluationIsPendingOrRunning() {
        DemoStateRepository repository = mock(DemoStateRepository.class);
        when(repository.hasActiveEvaluations()).thenReturn(true);
        DemoStateService service = service(repository, transactionTemplate());

        assertThatThrownBy(() -> service.reset("清空全部比赛数据"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("仍有运行中的核验或评测");

        verify(repository, never()).clearAll();
    }

    /**
     * 测试场景：核验任务处于 PARSING/RUNNING，或核验运行处于 PENDING/RUNNING。
     * 前置条件：仓储用固定 SQL 将上述四类活动状态汇总为活动核验工作。
     * 期望结果：服务拒绝清空，不能只因为评测已结束就删除核验所依赖的数据。
     * 断言重点：所有活动核验状态共享同一保护分支，并且不触发数据库删除。
     */
    @Test
    void rejectsResetWhenVerificationTaskOrRunIsActive() {
        DemoStateRepository repository = mock(DemoStateRepository.class);
        when(repository.hasActiveVerificationWork()).thenReturn(true);
        DemoStateService service = service(repository, transactionTemplate());

        assertThatThrownBy(() -> service.reset("清空全部比赛数据"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("仍有运行中的核验或评测");

        verify(repository, never()).clearAll();
    }

    /**
     * 测试场景：管理端提交非固定确认短语。
     * 前置条件：没有活动任务，但调用者没有明确确认不可逆的比赛数据清空操作。
     * 期望结果：抛出 DEMO_RESET_CONFIRMATION_INVALID，且不移动目录或访问删除 SQL。
     * 断言重点：确认语校验先于所有会改变状态的操作执行。
     */
    @Test
    void rejectsUnexpectedConfirmationPhrase() {
        DemoStateRepository repository = mock(DemoStateRepository.class);
        DemoStateService service = service(repository, transactionTemplate());

        assertThatThrownBy(() -> service.reset("确认"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("DEMO_RESET_CONFIRMATION_INVALID");

        verify(repository, never()).clearAll();
    }

    /**
     * 测试场景：三个受管目录已被移入暂存区后，固定七表删除发生数据库异常。
     * 前置条件：每个目录包含 .gitkeep 之外的运行文件，clearAll 模拟底层数据库故障。
     * 期望结果：目录从 .demo-reset 暂存位置恢复，原有文件没有丢失。
     * 断言重点：数据库提交失败时文件系统必须执行补偿，而非遗留空目录或暂存数据。
     */
    @Test
    void restoresManagedDirectoriesWhenDatabaseClearFails() throws Exception {
        writeRuntimeFile("uploads/upload.txt");
        writeRuntimeFile("skill-snapshots/snapshot.json");
        writeRuntimeFile("skill-runtime/runtime.txt");
        DemoStateRepository repository = mock(DemoStateRepository.class);
        doThrow(new DataAccessResourceFailureException("数据库不可用")).when(repository).clearAll();
        DemoStateService service = service(repository, transactionTemplate());

        assertThatThrownBy(() -> service.reset("清空全部比赛数据"))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(storageRoot.resolve("uploads/upload.txt")).exists();
        assertThat(storageRoot.resolve("skill-snapshots/snapshot.json")).exists();
        assertThat(storageRoot.resolve("skill-runtime/runtime.txt")).exists();
    }

    /**
     * 测试场景：没有活动任务且数据库固定七表清理成功。
     * 前置条件：三个目录均存在 .gitkeep 及一份运行产物，仓储清理后返回所有表的零计数。
     * 期望结果：状态投影为零，三个目录只保留 Git 边界文件。
     * 断言重点：成功路径删除暂存目录，并且不会把 .gitkeep 当作需清理的运行数据。
     */
    @Test
    void clearsAllTablesAndLeavesOnlyGitkeepInManagedDirectories() throws Exception {
        writeRuntimeFile("uploads/upload.txt");
        writeRuntimeFile("skill-snapshots/snapshot.json");
        writeRuntimeFile("skill-runtime/runtime.txt");
        DemoStateRepository repository = mock(DemoStateRepository.class);
        when(repository.counts()).thenReturn(Map.of(
                "claim", 0L,
                "verification_run", 0L,
                "verification_task", 0L,
                "evidence_snapshot", 0L,
                "release_binding", 0L,
                "skill_version", 0L,
                "evaluation_run", 0L));
        DemoStateService service = service(repository, transactionTemplate());

        DemoStateView state = service.reset("清空全部比赛数据");

        assertThat(state.tableCounts().values()).allMatch(count -> count == 0L);
        assertThat(state.storageEmpty().values()).allMatch(Boolean::booleanValue);
        assertDirectoryContainsOnlyGitkeep(storageRoot.resolve("uploads"));
        assertDirectoryContainsOnlyGitkeep(storageRoot.resolve("skill-snapshots"));
        assertDirectoryContainsOnlyGitkeep(storageRoot.resolve("skill-runtime"));
    }

    /** 建立服务实例；目录交换使用真实实现，事务模板只执行回调以隔离数据库基础设施。 */
    private DemoStateService service(DemoStateRepository repository, TransactionTemplate transactionTemplate) {
        return new DemoStateService(repository, new ManagedStorageSwap(storageRoot), transactionTemplate);
    }

    /** 让 Mock 事务模板同步执行服务提供的数据库清理回调，保留异常向外传播的真实语义。 */
    @SuppressWarnings("unchecked")
    private static TransactionTemplate transactionTemplate() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
                    java.util.function.Consumer<org.springframework.transaction.TransactionStatus> callback =
                            invocation.getArgument(0);
                    callback.accept(mock(org.springframework.transaction.TransactionStatus.class));
                    return null;
                })
                .when(template)
                .executeWithoutResult(org.mockito.ArgumentMatchers.any());
        return template;
    }

    /** 写入一个运行期文件并同时创建目录必须保留的 .gitkeep，以模拟版本库初始化后的真实布局。 */
    private void writeRuntimeFile(String relativePath) throws Exception {
        Path target = storageRoot.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target.getParent().resolve(".gitkeep"), "");
        Files.writeString(target, "比赛运行产物");
    }

    /** 将目录内容投影为文件名，精确确认成功清理仅保留版本库需要的 Git 边界文件。 */
    private static void assertDirectoryContainsOnlyGitkeep(Path directory) throws Exception {
        try (Stream<Path> children = Files.list(directory)) {
            assertThat(children.map(path -> path.getFileName().toString()).toList()).containsExactly(".gitkeep");
        }
    }
}
