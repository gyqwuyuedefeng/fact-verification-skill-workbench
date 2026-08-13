package com.hsmap.factverification.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.agent.FactVerificationAgentRunner;
import com.hsmap.factverification.claim.persistence.ClaimRepository;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.document.DeterministicDocumentParser;
import com.hsmap.factverification.release.persistence.ReleaseBindingRepository;
import com.hsmap.factverification.run.persistence.VerificationRunRepository;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import com.hsmap.factverification.task.persistence.VerificationTaskRepository;
import java.net.URI;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 被测试对象：VerificationTaskService 的 PRIMARY 后台执行边界。
 * 测试目的：保证启动接口先返回运行标识，并保证后台线程遇到未检查运行错误时仍将任务收口为失败终态。
 * 覆盖范围：异步返回、成功状态持久化、运行时 Error 的失败记录与任务状态迁移。
 * 前置条件：仓储和 Agent 使用可观测 Mock，任务已完成材料解析并处于 READY 状态。
 */
class VerificationTaskAsyncExecutionTest {

    /**
     * 测试场景：Agent 被闩锁阻塞时调用 start。
     * 前置条件：READY 任务尚无 PRIMARY，模型仅在测试释放闩锁后返回空主张结果。
     * 期望结果：start 不等待模型完成，模型释放后任务迁移为 COMPLETED。
     * 断言重点：HTTP 调用的 future 提前完成，后台线程随后写入成功终态。
     */
    @Test
    void returnsBeforePrimaryAgentCompletes() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        VerificationTaskRepository tasks = mock(VerificationTaskRepository.class);
        VerificationRunRepository runs = mock(VerificationRunRepository.class);
        FactVerificationAgentRunner agent = mock(FactVerificationAgentRunner.class);
        ObjectMapper mapper = new ObjectMapper();
        OffsetDateTime now = OffsetDateTime.of(2026, 8, 12, 0, 0, 0, 0, ZoneOffset.UTC);
        var ready = new VerificationTaskRepository.TaskState(
                taskId,
                "task-request-async",
                "核验收入",
                "TEXT",
                "message.txt",
                "a".repeat(64),
                "READY",
                false,
                snapshotId,
                "{\"blocks\":[]}",
                "b".repeat(64),
                null,
                now);
        when(tasks.findById(taskId)).thenReturn(Optional.of(ready));
        when(runs.findByTaskAndType(taskId, "PRIMARY")).thenReturn(Optional.empty());

        CountDownLatch agentEntered = new CountDownLatch(1);
        CountDownLatch releaseAgent = new CountDownLatch(1);
        when(agent.run(any(), eq(snapshotId), eq("TASK"), eq(taskId), any(), any(), any()))
                .thenAnswer(invocation -> {
                    agentEntered.countDown();
                    releaseAgent.await(5, TimeUnit.SECONDS);
                    return mapper.readTree("{\"claims\":[]}");
                });

        var service = new VerificationTaskService(
                tasks,
                runs,
                mock(ClaimRepository.class),
                mock(ReleaseBindingRepository.class),
                mock(SkillVersionRepository.class),
                mock(DeterministicDocumentParser.class),
                agent,
                properties(),
                mapper,
                new CanonicalJsonHasher(mapper));

        var caller = Executors.newSingleThreadExecutor();
        try {
            var future = caller.submit(() -> service.start(taskId, "run-request-async", "BASELINE"));
            assertThat(agentEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(future.isDone()).as("HTTP 启动调用不应等待模型完成").isTrue();
            releaseAgent.countDown();
            future.get(1, TimeUnit.SECONDS);
            verify(tasks, timeout(1_000)).transition(eq(taskId), eq("RUNNING"), eq("COMPLETED"), any());
        } finally {
            releaseAgent.countDown();
            caller.shutdownNow();
            service.shutdownExecutor();
        }
    }

    /**
     * 测试场景：Agent 在后台线程抛出 NoClassDefFoundError 一类未检查 Error。
     * 前置条件：READY 任务已经创建 PRIMARY 运行，但错误发生在 AgentScope/MCP 客户端初始化阶段。
     * 期望结果：运行记录标记失败，任务从 RUNNING 迁移到 FAILED，页面轮询不会永久等待。
     * 断言重点：失败码固定为 PRIMARY_EXECUTION_FAILED，并且终态迁移一定发生。
     */
    @Test
    void marksPrimaryTaskFailedWhenAgentThrowsAnError() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        VerificationTaskRepository tasks = mock(VerificationTaskRepository.class);
        VerificationRunRepository runs = mock(VerificationRunRepository.class);
        FactVerificationAgentRunner agent = mock(FactVerificationAgentRunner.class);
        ObjectMapper mapper = new ObjectMapper();
        OffsetDateTime now = OffsetDateTime.of(2026, 8, 12, 0, 0, 0, 0, ZoneOffset.UTC);
        var ready = new VerificationTaskRepository.TaskState(
                taskId,
                "task-request-error",
                "核验收入",
                "TEXT",
                "message.txt",
                "a".repeat(64),
                "READY",
                false,
                snapshotId,
                "{\"blocks\":[]}",
                "b".repeat(64),
                null,
                now);
        when(tasks.findById(taskId)).thenReturn(Optional.of(ready));
        when(runs.findByTaskAndType(taskId, "PRIMARY")).thenReturn(Optional.empty());
        when(agent.run(any(), eq(snapshotId), eq("TASK"), eq(taskId), any(), any(), any()))
                .thenThrow(new NoClassDefFoundError("com/networknt/schema/dialect/Dialects"));

        var service = new VerificationTaskService(
                tasks,
                runs,
                mock(ClaimRepository.class),
                mock(ReleaseBindingRepository.class),
                mock(SkillVersionRepository.class),
                mock(DeterministicDocumentParser.class),
                agent,
                properties(),
                mapper,
                new CanonicalJsonHasher(mapper));

        CountDownLatch backgroundErrorObserved = new CountDownLatch(1);
        AtomicReference<Throwable> backgroundError = new AtomicReference<>();
        Thread.UncaughtExceptionHandler originalHandler = Thread.getDefaultUncaughtExceptionHandler();
        // PRIMARY 按设计在完成失败持久化后继续抛出 JVM Error。测试必须显式接住并等待该线程结束，
        // 否则 Surefire 开始卸载测试类后，迟到的异常会污染下一模块日志并形成假性 NoClassDefFoundError。
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (thread.getName().startsWith("fact-verification-primary")) {
                backgroundError.set(failure);
                backgroundErrorObserved.countDown();
            } else if (originalHandler != null) {
                originalHandler.uncaughtException(thread, failure);
            }
        });

        try {
            service.start(taskId, "run-request-error", "BASELINE");

            verify(runs, timeout(1_000))
                    .fail(any(), eq("PRIMARY_EXECUTION_FAILED"), any(), any());
            verify(tasks, timeout(1_000)).transition(eq(taskId), eq("RUNNING"), eq("FAILED"), any());
            assertThat(backgroundErrorObserved.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(backgroundError.get()).isInstanceOf(NoClassDefFoundError.class);
        } finally {
            service.shutdownExecutor();
            Thread.setDefaultUncaughtExceptionHandler(originalHandler);
        }
    }

    private static WorkbenchProperties properties() {
        return new WorkbenchProperties(
                new WorkbenchProperties.DatabaseBoundary("kjjr_inx_brain", "test", false),
                Path.of("target/test-async"),
                Path.of("../evals/manifest.json"),
                Path.of("../skills/company-material-fact-check"),
                new WorkbenchProperties.Model("http://model.invalid", "/v1/chat/completions", "company-qwen", ""),
                URI.create("http://127.0.0.1:19091/mcp"));
    }
}
