package com.hsmap.factverification.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 被测试对象：{@link DemoImportIdempotency} 的单进程有界 CompletableFuture 结果协调。
 * 测试目的：证明导入请求的同键并发与成功重试只执行一次，同时不同导入操作互不串用结果。
 * 覆盖范围：并发等待首次结果、成功后重试，以及 snapshot/builtin 操作类型隔离。
 * 前置条件：supplier 只返回内存中的脱敏状态，不读取网络流、不调用快照 API，也不修改数据库或受管目录。
 */
class DemoImportIdempotencyTest {

    /**
     * 测试场景：两个线程同时用相同幂等键执行自定义快照导入。
     * 前置条件：首次 supplier 进入后由闩锁暂停，使第二线程确定命中未完成 Future。
     * 期望结果：两个调用得到同一个首次状态，破坏性 supplier 只执行一次。
     * 断言重点：等待不能持有管理写锁；本协调器只围绕结果 Future 做短临界区。
     */
    @Test
    void executesConcurrentSnapshotImportOnlyOnce() throws Exception {
        DemoImportIdempotency idempotency = new DemoImportIdempotency();
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        DemoStateView firstState = new DemoStateView(Map.of("skill_version", 4L), Map.of("uploads", false));

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> idempotency.execute(
                    DemoImportIdempotency.Operation.SNAPSHOT, "shared-import-key", () -> {
                        executions.incrementAndGet();
                        ownerStarted.countDown();
                        await(releaseOwner);
                        return firstState;
                    }));
            assertThat(ownerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> idempotency.execute(
                    DemoImportIdempotency.Operation.SNAPSHOT, "shared-import-key", () -> {
                        executions.incrementAndGet();
                        return new DemoStateView(Map.of(), Map.of());
                    }));
            releaseOwner.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(firstState);
            assertThat(second.get(5, TimeUnit.SECONDS)).isSameAs(firstState);
            assertThat(executions).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 测试场景：首次自定义导入成功后客户端因响应丢失而用同键重试。
     * 前置条件：第二次 supplier 若执行会返回不同状态并增加计数。
     * 期望结果：重试直接恢复首次成功结果，不再次执行破坏性导入。
     * 断言重点：幂等结果在当前进程生命周期内保持稳定。
     */
    @Test
    void returnsStableFirstResultForSuccessfulRetry() {
        DemoImportIdempotency idempotency = new DemoImportIdempotency();
        AtomicInteger executions = new AtomicInteger();
        DemoStateView firstState = new DemoStateView(Map.of("skill_version", 4L), Map.of("uploads", false));

        DemoStateView first = idempotency.execute(
                DemoImportIdempotency.Operation.SNAPSHOT, "successful-retry-key", () -> {
                    executions.incrementAndGet();
                    return firstState;
                });
        DemoStateView retry = idempotency.execute(
                DemoImportIdempotency.Operation.SNAPSHOT, "successful-retry-key", () -> {
                    executions.incrementAndGet();
                    return new DemoStateView(Map.of(), Map.of());
                });

        assertThat(first).isSameAs(firstState);
        assertThat(retry).isSameAs(firstState);
        assertThat(executions).hasValue(1);
    }

    /**
     * 测试场景：自定义快照与内置 fixture 恰好收到相同文本幂等键。
     * 前置条件：两个 supplier 分别代表不同破坏性操作类型。
     * 期望结果：两个操作各执行一次，不能把自定义导入结果复用于内置导入。
     * 断言重点：缓存键必须由操作类型和请求键共同组成。
     */
    @Test
    void isolatesSameKeyByImportOperationType() {
        DemoImportIdempotency idempotency = new DemoImportIdempotency();
        AtomicInteger executions = new AtomicInteger();

        idempotency.execute(DemoImportIdempotency.Operation.SNAPSHOT, "same-text-key", () -> {
            executions.incrementAndGet();
            return new DemoStateView(Map.of(), Map.of());
        });
        idempotency.execute(DemoImportIdempotency.Operation.BUILTIN, "same-text-key", () -> {
            executions.incrementAndGet();
            return new DemoStateView(Map.of(), Map.of());
        });

        assertThat(executions).hasValue(2);
    }

    /** 测试线程等待辅助；中断会恢复标志并转成明确失败，避免静默吞掉并发测试异常。 */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("等待并发测试闩锁超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("并发测试被中断", exception);
        }
    }
}
