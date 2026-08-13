package com.hsmap.factverification.demo;

import com.hsmap.factverification.shared.ServiceException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 比赛演示状态查询、空状态保护与完整清空应用服务。
 *
 * <p>该服务只协调固定七张表和三个受管目录：先拒绝活动任务，再可恢复地交换目录，最后通过 TransactionTemplate 清理数据库。
 */
@Service
public class DemoStateService {

    private static final String CONFIRMATION_PHRASE = "清空全部比赛数据";
    private static final int MAX_RESET_IDEMPOTENCY_KEYS = 64;

    private final DemoStateRepository repository;
    private final ManagedStorageSwap storageSwap;
    private final TransactionTemplate resetTransactionTemplate;
    private final Object idempotencyMonitor = new Object();
    private final Map<String, CompletableFuture<DemoStateView>> resetResults = new LinkedHashMap<>();

    /**
     * 注入固定状态仓储、受管目录交换器和底层事务管理器。
     *
     * <p>此处新建只属于 reset 的 REQUIRES_NEW 模板，不修改也不复用其他业务服务可能正在参与的 REQUIRED 模板。
     */
    public DemoStateService(
            DemoStateRepository repository,
            ManagedStorageSwap storageSwap,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.storageSwap = storageSwap;
        this.resetTransactionTemplate = new TransactionTemplate(transactionManager);
        this.resetTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
    public DemoStateView reset(String idempotencyKey, String confirmationPhrase) {
        // 确认语是每次破坏性请求的独立安全边界，必须先于读取或写入幂等结果执行，不能被历史成功结果绕过。
        requireExactConfirmationPhrase(confirmationPhrase);
        CompletableFuture<DemoStateView> result;
        boolean owner = false;
        synchronized (idempotencyMonitor) {
            result = resetResults.get(idempotencyKey);
            if (result == null) {
                if (resetResults.size() >= MAX_RESET_IDEMPOTENCY_KEYS) {
                    throw new ServiceException("DEMO_RESET_IDEMPOTENCY_LIMIT_REACHED", "当前演示进程的清空幂等键已达到安全上限");
                }
                result = new CompletableFuture<>();
                resetResults.put(idempotencyKey, result);
                owner = true;
            }
        }
        if (!owner) {
            return completedResult(result);
        }
        try {
            DemoStateView resetState = performReset(confirmationPhrase);
            result.complete(resetState);
            return resetState;
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
            throw exception;
        }
    }

    /**
     * 执行首次幂等请求的实际破坏性操作。
     *
     * <p>只有独立事务的 executeWithoutResult 正常返回（即已完成 commit）后才会销毁目录暂存；异常路径始终恢复目录。
     */
    private DemoStateView performReset(String confirmationPhrase) {
        requireNoActiveWork();
        ManagedStorageSwap.PreparedStorageSwap prepared = storageSwap.prepare(UUID.randomUUID());
        try {
            resetTransactionTemplate.executeWithoutResult(status -> repository.clearAll());
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

    /** 校验固定确认短语；失败时不创建幂等键，避免无效请求挤占有限的单进程安全容量。 */
    private static void requireExactConfirmationPhrase(String confirmationPhrase) {
        if (!CONFIRMATION_PHRASE.equals(confirmationPhrase)) {
            throw new ServiceException("DEMO_RESET_CONFIRMATION_INVALID", "确认短语必须为“清空全部比赛数据”");
        }
    }

    /** 等待并返回同键首次请求的稳定结果；首次失败也稳定复现同一业务或基础设施异常。 */
    private static DemoStateView completedResult(CompletableFuture<DemoStateView> result) {
        try {
            return result.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
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

    /**
     * 导出前确认所有会继续写表或附件的核验、评测均已结束。
     *
     * <p>这是只读快照的一致性门禁，不改变任何任务状态；调用方必须在读取第一张表前执行。
     */
    public void requireQuiescentForSnapshotExport() {
        if (repository.hasActiveEvaluations() || repository.hasActiveVerificationWork()) {
            throw new ServiceException("DEMO_SNAPSHOT_ACTIVE_WORK", "仍有运行中的核验或评测，不能导出快照");
        }
    }

    /** 校验快照导入专用确认短语，防止复用清空短语或普通上传请求误触破坏性恢复。 */
    public void requireImportConfirmationPhrase(String confirmationPhrase) {
        if (!"导入快照".equals(confirmationPhrase)) {
            throw new ServiceException("DEMO_SNAPSHOT_CONFIRMATION_INVALID", "确认短语必须为“导入快照”");
        }
    }

    /** 活动评测、解析或运行中的核验都可能继续写入数据，因此统一拒绝管理端清空操作。 */
    private void requireNoActiveWork() {
        if (repository.hasActiveEvaluations() || repository.hasActiveVerificationWork()) {
            throw new ServiceException("DEMO_RESET_ACTIVE_WORK", "仍有运行中的核验或评测，不能清空比赛数据");
        }
    }
}
