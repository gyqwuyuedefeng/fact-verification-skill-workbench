package com.hsmap.factverification.evaluation;

import com.hsmap.factverification.evaluation.persistence.EvaluationRunRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 在单实例后端启动时收口上一进程遗留的未完成评测。
 *
 * <p>MVP 不引入持久任务队列或跨进程续跑；因此旧 JVM 的评测线程消失后，最诚实的恢复语义是 INTERRUPTED，由管理员创建新批次重跑。该组件在数据库身份门禁之后运行，禁止在未确认
 * database/schema 前执行状态写入。
 */
@Component
@Order(100)
public final class EvaluationRestartRecovery implements ApplicationRunner {

    private final EvaluationRunRepository evaluations;

    public EvaluationRestartRecovery(EvaluationRunRepository evaluations) {
        this.evaluations = evaluations;
    }

    /** 应用每次启动只执行一次幂等收口；没有遗留记录时更新行数为零。 */
    @Override
    public void run(ApplicationArguments arguments) {
        evaluations.interruptIncompleteAfterRestart();
    }
}
