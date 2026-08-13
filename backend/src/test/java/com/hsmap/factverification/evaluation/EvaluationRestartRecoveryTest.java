package com.hsmap.factverification.evaluation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hsmap.factverification.evaluation.persistence.EvaluationRunRepository;
import org.junit.jupiter.api.Test;

/**
 * 被测试对象：{@link EvaluationRestartRecovery} 的应用启动恢复动作。
 * 测试目的：证明进程退出后不可能继续执行的 PENDING/RUNNING 评测会被明确收口，而不会在管理页永久显示运行中。
 * 覆盖范围：Spring ApplicationRunner 入口到评测仓库恢复命令的单次调用。
 * 前置条件：仓库使用 Mock；实际状态更新条件和不可覆盖报告约束由仓库 SQL 与数据库契约测试保障。
 */
class EvaluationRestartRecoveryTest {

    /**
     * 测试场景：新后端进程启动，旧进程中的评测工作线程已经不存在。
     * 前置条件：构造一个可观测调用次数的评测仓库，不传入额外启动参数。
     * 期望结果：恢复组件恰好执行一次未完成评测中断命令。
     * 断言重点：恢复必须是启动路径的确定性动作，不能依赖管理员手工修改数据库。
     */
    @Test
    void interruptsEvaluationsThatCannotSurviveAProcessRestart() throws Exception {
        EvaluationRunRepository repository = mock(EvaluationRunRepository.class);

        new EvaluationRestartRecovery(repository).run(null);

        verify(repository).interruptIncompleteAfterRestart();
    }
}
