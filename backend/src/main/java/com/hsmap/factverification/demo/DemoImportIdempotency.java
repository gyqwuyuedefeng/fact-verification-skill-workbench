package com.hsmap.factverification.demo;

import com.hsmap.factverification.shared.ServiceException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 两个演示导入 POST 的单进程有界幂等结果协调器。
 *
 * <p>它复用 reset 已验证的 CompletableFuture 所有者/等待者模式，但以“操作类型 + 请求键”隔离自定义快照和内置 fixture。
 * 当前单实例比赛 MVP 不新增表或分布式协调；进程重启后的请求仍由空状态前置条件保护。
 */
@Component
public final class DemoImportIdempotency {

    private static final int MAX_IMPORT_IDEMPOTENCY_KEYS = 128;

    private final Object monitor = new Object();
    private final Map<OperationKey, CompletableFuture<DemoStateView>> results = new LinkedHashMap<>();

    /** 两种破坏性导入必须共享实现但不能共享同一文本 key 的结果。 */
    public enum Operation {
        SNAPSHOT,
        BUILTIN
    }

    /**
     * 返回指定操作键的首次稳定结果，并让同键并发请求等待同一个 Future。
     *
     * <p>supplier 在 monitor 外执行，因此网络流落盘、ZIP 校验和管理写锁周期都不会被本缓存临界区包围。首次异常也稳定复现，避免失败重试交错执行第二次破坏性导入。
     *
     * @param operation 固定导入类型，用于隔离相同文本请求键
     * @param idempotencyKey 已由 HTTP 层使用 RequestId 完成格式校验的键
     * @param firstOperation 只有首次所有者会执行的完整导入与状态读取
     * @return 首次请求返回的脱敏状态对象
     */
    public DemoStateView execute(
            Operation operation, String idempotencyKey, Supplier<DemoStateView> firstOperation) {
        OperationKey key = new OperationKey(operation, idempotencyKey);
        CompletableFuture<DemoStateView> result;
        boolean owner = false;
        synchronized (monitor) {
            result = results.get(key);
            if (result == null) {
                if (results.size() >= MAX_IMPORT_IDEMPOTENCY_KEYS) {
                    throw new ServiceException(
                            "DEMO_IMPORT_IDEMPOTENCY_LIMIT_REACHED", "当前演示进程的导入幂等键已达到安全上限");
                }
                result = new CompletableFuture<>();
                results.put(key, result);
                owner = true;
            }
        }
        if (!owner) {
            return completedResult(result);
        }
        try {
            DemoStateView state = firstOperation.get();
            result.complete(state);
            return state;
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
            throw exception;
        }
    }

    /** 等待首次请求；业务异常保持原类型和稳定 code，不包裹成新的泄漏性异常。 */
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

    /** 缓存身份同时包含固定操作类型与已校验请求键。 */
    private record OperationKey(Operation operation, String idempotencyKey) {}
}
