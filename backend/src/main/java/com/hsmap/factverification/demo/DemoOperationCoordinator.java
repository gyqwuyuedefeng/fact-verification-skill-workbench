package com.hsmap.factverification.demo;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * test-profile 比赛演示状态的单进程破坏性操作协调器。
 *
 * <p>reset、snapshot import 与 snapshot export 共享这一把公平锁，使“检查空白/静止→事务→目录交换”成为不可交错的最小边界。
 * 它不提供分布式锁、租约或通用任务调度，只服务当前单实例比赛演示能力。
 */
@Component
public final class DemoOperationCoordinator {

    private final ReentrantLock exclusiveBoundary = new ReentrantLock(true);

    /** 在公平独占边界内执行有返回值的演示状态操作，异常不改变原语义。 */
    public <T> T exclusively(Supplier<T> operation) {
        exclusiveBoundary.lock();
        try {
            return operation.get();
        } finally {
            exclusiveBoundary.unlock();
        }
    }

    /** 在同一独占边界内执行无返回值操作，避免各服务自行持有不同锁。 */
    public void exclusively(Runnable operation) {
        exclusively(() -> {
            operation.run();
            return null;
        });
    }
}
