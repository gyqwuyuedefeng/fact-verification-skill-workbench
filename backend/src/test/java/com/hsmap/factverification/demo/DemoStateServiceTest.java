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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

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
        DemoStateService service = service(repository, new RecordingTransactionManager());

        assertThatThrownBy(() -> service.reset("active-evaluation", "清空全部比赛数据"))
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
        DemoStateService service = service(repository, new RecordingTransactionManager());

        assertThatThrownBy(() -> service.reset("active-verification", "清空全部比赛数据"))
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
        DemoStateService service = service(repository, new RecordingTransactionManager());

        assertThatThrownBy(() -> service.reset("invalid-confirmation", "确认"))
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
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        DemoStateService service = service(repository, transactionManager);

        assertThatThrownBy(() -> service.reset("database-failure", "清空全部比赛数据"))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(transactionManager.rollbacks()).isEqualTo(1);
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
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        DemoStateService service = service(repository, transactionManager);

        DemoStateView state = service.reset("successful-reset", "清空全部比赛数据");

        assertThat(transactionManager.propagations()).containsExactly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(transactionManager.commits()).isEqualTo(1);
        assertThat(state.tableCounts().values()).allMatch(count -> count == 0L);
        assertThat(state.storageEmpty().values()).allMatch(Boolean::booleanValue);
        assertDirectoryContainsOnlyGitkeep(storageRoot.resolve("uploads"));
        assertDirectoryContainsOnlyGitkeep(storageRoot.resolve("skill-snapshots"));
        assertDirectoryContainsOnlyGitkeep(storageRoot.resolve("skill-runtime"));
    }

    /**
     * 测试场景：同一幂等键的首次清空已经成功，随后外部导入产生了新的比赛数据。
     * 前置条件：同一进程仍保留首次成功结果，第二次请求使用完全相同的幂等键。
     * 期望结果：第二次调用返回首次结果但绝不再次调用 clearAll，不能删除后来导入的数据。
     * 断言重点：缓存必须保存成功结果，而不是仅保存“已处理”标记后重新执行当前清空逻辑。
     */
    @Test
    void returnsFirstResultWithoutClearingAgainForSameIdempotencyKey() {
        DemoStateRepository repository = mock(DemoStateRepository.class);
        Map<String, Long> firstCounts = zeroCounts();
        when(repository.counts()).thenReturn(firstCounts, Map.of("claim", 1L));
        DemoStateService service = service(repository, new RecordingTransactionManager());

        DemoStateView first = service.reset("repeat-reset", "清空全部比赛数据");
        DemoStateView repeated = service.reset("repeat-reset", "清空全部比赛数据");

        assertThat(repeated).isEqualTo(first);
        verify(repository, org.mockito.Mockito.times(1)).clearAll();
    }

    /**
     * 测试场景：两个管理请求并发提交相同幂等键。
     * 前置条件：首次数据库清理在事务回调内暂停，第二个线程在首次完成前进入服务。
     * 期望结果：只有第一个线程进入 clearAll，两个调用最终获得同一个成功投影。
     * 断言重点：同键竞争不能因“先查再写”竞态而执行两次破坏性清空操作。
     */
    @Test
    void executesDestructiveResetOnlyOnceWhenSameKeyArrivesConcurrently() throws Exception {
        DemoStateRepository repository = mock(DemoStateRepository.class);
        when(repository.counts()).thenReturn(zeroCounts());
        CountDownLatch clearEntered = new CountDownLatch(1);
        CountDownLatch allowClear = new CountDownLatch(1);
        AtomicInteger clearCalls = new AtomicInteger();
        doAnswer(invocation -> {
                    clearCalls.incrementAndGet();
                    clearEntered.countDown();
                    assertThat(allowClear.await(5, TimeUnit.SECONDS)).isTrue();
                    return null;
                })
                .when(repository)
                .clearAll();
        DemoStateService service = service(repository, new RecordingTransactionManager());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DemoStateView> first = executor.submit(() -> service.reset("concurrent-reset", "清空全部比赛数据"));
            assertThat(clearEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<DemoStateView> repeated =
                    executor.submit(() -> service.reset("concurrent-reset", "清空全部比赛数据"));
            allowClear.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(repeated.get(5, TimeUnit.SECONDS));
            assertThat(clearCalls).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 测试场景：数据库清理运行在调用者可能已有的事务范围内。
     * 前置条件：记录型事务管理器观察 TransactionTemplate 请求的传播属性与实际提交回调。
     * 期望结果：reset 使用 REQUIRES_NEW 且成功后恰好提交一次，目录删除不会发生在事务提交之前。
     * 断言重点：不能复用默认 REQUIRED 的共享模板，否则外层回滚会使文件和数据库状态分叉。
     */
    @Test
    void usesDedicatedRequiresNewTransactionBeforeFinalizingStorageSwap() {
        DemoStateRepository repository = mock(DemoStateRepository.class);
        when(repository.counts()).thenReturn(zeroCounts());
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        doAnswer(invocation -> {
                    assertThat(transactionManager.commits()).isZero();
                    return null;
                })
                .when(repository)
                .clearAll();
        DemoStateService service = service(repository, transactionManager);

        service.reset("requires-new", "清空全部比赛数据");

        assertThat(transactionManager.propagations()).containsExactly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(transactionManager.commits()).isEqualTo(1);
    }

    /** 建立服务实例；目录交换使用真实实现，事务管理器记录独立提交行为而不连接数据库。 */
    private DemoStateService service(DemoStateRepository repository, RecordingTransactionManager transactionManager) {
        return new DemoStateService(repository, new ManagedStorageSwap(storageRoot), transactionManager);
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

    /** 生成固定七表零计数，模拟数据库提交成功后的演示空状态。 */
    private static Map<String, Long> zeroCounts() {
        return Map.of(
                "claim", 0L,
                "verification_run", 0L,
                "verification_task", 0L,
                "evidence_snapshot", 0L,
                "release_binding", 0L,
                "skill_version", 0L,
                "evaluation_run", 0L);
    }

    /**
     * 测试专用的最小事务管理器。
     *
     * <p>它不模拟数据库，只记录 Spring 实际请求的传播级别及 commit/rollback 回调，用于证明 reset 没有复用 REQUIRED 模板。
     */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final java.util.List<Integer> propagations = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
            propagations.add(definition.getPropagationBehavior());
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks.incrementAndGet();
        }

        private java.util.List<Integer> propagations() {
            return propagations;
        }

        private int commits() {
            return commits.get();
        }

        private int rollbacks() {
            return rollbacks.get();
        }
    }
}
