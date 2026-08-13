package com.hsmap.factverification.demo;

import com.hsmap.factverification.shared.ServiceException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 比赛演示状态查询、空状态保护与完整清空应用服务。
 *
 * <p>该服务只协调固定七张表和三个受管目录：先拒绝活动任务，再可恢复地交换目录，最后通过 TransactionTemplate 清理数据库。
 */
@Service
public class DemoStateService {

    private static final String CONFIRMATION_PHRASE = "清空全部比赛数据";

    private final DemoStateRepository repository;
    private final ManagedStorageSwap storageSwap;
    private final TransactionTemplate transactionTemplate;

    /** 注入固定状态仓储、受管目录交换器和 Spring 数据库事务模板。 */
    public DemoStateService(
            DemoStateRepository repository, ManagedStorageSwap storageSwap, TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.storageSwap = storageSwap;
        this.transactionTemplate = transactionTemplate;
    }

    /** 返回固定比赛数据表和受管目录的当前状态，不向客户端暴露真实文件路径。 */
    public DemoStateView status() {
        return new DemoStateView(repository.counts(), storageSwap.blankState());
    }

    /**
     * 清空全部比赛数据并返回清空后的状态。
     *
     * <p>目录先移动到 .demo-reset 暂存区，以便数据库事务失败时恢复；只有事务提交成功后才删除暂存数据。
     */
    public DemoStateView reset(String confirmationPhrase) {
        if (!CONFIRMATION_PHRASE.equals(confirmationPhrase)) {
            throw new ServiceException("DEMO_RESET_CONFIRMATION_INVALID", "确认短语必须为“清空全部比赛数据”");
        }
        requireNoActiveWork();
        ManagedStorageSwap.PreparedStorageSwap prepared = storageSwap.prepare(UUID.randomUUID());
        try {
            transactionTemplate.executeWithoutResult(status -> repository.clearAll());
        } catch (RuntimeException exception) {
            try {
                storageSwap.restore(prepared);
            } catch (RuntimeException restoreException) {
                exception.addSuppressed(restoreException);
            }
            throw exception;
        }
        storageSwap.commit(prepared);
        return status();
    }

    /**
     * 供快照导入在写入前复用的空状态前置条件。
     *
     * <p>除 .gitkeep 外任何目录内容、或七张业务表任一非零计数都会拒绝导入，防止覆盖正在保留的比赛证据。
     */
    public void requireBlank() {
        requireNoActiveWork();
        boolean tableBlank = repository.counts().values().stream().allMatch(count -> count == 0L);
        boolean storageBlank = storageSwap.blankState().values().stream().allMatch(Boolean::booleanValue);
        if (!tableBlank || !storageBlank) {
            throw new ServiceException("DEMO_STATE_NOT_BLANK", "当前比赛数据或运行目录不为空，不能导入快照");
        }
    }

    /** 活动评测、解析或运行中的核验都可能继续写入数据，因此统一拒绝管理端清空操作。 */
    private void requireNoActiveWork() {
        if (repository.hasActiveEvaluations() || repository.hasActiveVerificationWork()) {
            throw new ServiceException("DEMO_RESET_ACTIVE_WORK", "仍有运行中的核验或评测，不能清空比赛数据");
        }
    }
}
