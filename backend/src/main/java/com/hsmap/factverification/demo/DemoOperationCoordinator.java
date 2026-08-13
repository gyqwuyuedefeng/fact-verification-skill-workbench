package com.hsmap.factverification.demo;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * test-profile 比赛演示状态的单进程文件读写协调器。
 *
 * <p>reset、snapshot import 与本地 snapshot 生成使用写锁；普通上传与 Skill 冻结使用读锁。这使
 * “数据库事务↔受管文件生产/交换”在单实例内不交错，但多个普通文件生产者仍可并行。
 * 它不提供分布式锁、租约或通用任务调度，只服务当前单实例比赛演示能力。
 */
@Component
public final class DemoOperationCoordinator {

    private final ReentrantReadWriteLock fileStateBoundary = new ReentrantReadWriteLock(true);

    /** 在公平独占边界内执行有返回值的演示状态操作，异常不改变原语义。 */
    public <T> T exclusively(Supplier<T> operation) {
        fileStateBoundary.writeLock().lock();
        try {
            return operation.get();
        } finally {
            fileStateBoundary.writeLock().unlock();
        }
    }

    /** 在同一独占边界内执行无返回值操作，避免各服务自行持有不同锁。 */
    public void exclusively(Runnable operation) {
        exclusively(() -> {
            operation.run();
            return null;
        });
    }

    /** 普通文件生产的完整周期持有共享读锁，与管理目录替换互斥。 */
    public <T> T duringFileProduction(Supplier<T> operation) {
        fileStateBoundary.readLock().lock();
        try {
            return operation.get();
        } finally {
            fileStateBoundary.readLock().unlock();
        }
    }

    /** 无返回值的文件生产读锁入口，便于上传和冻结服务包裹既有流程。 */
    public void duringFileProduction(Runnable operation) {
        duringFileProduction(() -> {
            operation.run();
            return null;
        });
    }
}
