package com.hsmap.factverification.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.agent.FactVerificationAgentRunner;
import com.hsmap.factverification.claim.persistence.ClaimRepository;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.demo.DemoOperationCoordinator;
import com.hsmap.factverification.document.DeterministicDocumentParser;
import com.hsmap.factverification.document.DocumentSnapshot;
import com.hsmap.factverification.release.persistence.ReleaseBindingRepository;
import com.hsmap.factverification.run.persistence.VerificationRunRepository;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import com.hsmap.factverification.task.persistence.VerificationTaskRepository;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 被测试对象：{@link VerificationTaskService} 与演示状态读写协调器的文件生产边界。
 * 测试目的：证明普通材料上传在文件落盘、解析和数据库固定的完整周期持有读锁。
 * 覆盖范围：管理写锁与上传读锁的互斥、锁释放后的正常文件和任务状态写入。
 * 前置条件：所有仓储与解析器使用 Mock，文件仅写 JUnit 临时 storageRoot。
 */
class VerificationTaskFileCoordinationTest {

    @TempDir
    Path storageRoot;

    /**
     * 测试场景：快照导入/reset 一类管理操作已持有写锁，普通任务同时尝试上传文字材料。
     * 前置条件：管理线程用闩锁稳定占有写锁，上传线程已开始调用且目标文件尚不存在。
     * 期望结果：写锁释放前不产生 message.txt，释放后上传完成并固定任务。
     * 断言重点：普通文件不能出现在管理目录安装/补偿中间，且不因锁而丢失正常上传。
     */
    @Test
    void blocksUploadFileLifecycleWhileManagementWriteLockIsHeld() throws Exception {
        UUID taskId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.of(2026, 8, 13, 0, 0, 0, 0, ZoneOffset.UTC);
        VerificationTaskRepository tasks = mock(VerificationTaskRepository.class);
        VerificationTaskRepository.TaskState uploaded = new VerificationTaskRepository.TaskState(
                taskId,
                "upload-lock-request",
                "说明",
                "TEXT",
                "pending-upload",
                "0".repeat(64),
                "UPLOADED",
                false,
                null,
                null,
                null,
                null,
                createdAt);
        when(tasks.findById(taskId)).thenReturn(Optional.of(uploaded));
        when(tasks.attachMaterial(eq(taskId), any(), any(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(tasks.markReady(eq(taskId), any(), any(), any(), any(), any())).thenReturn(1);
        DeterministicDocumentParser parser = mock(DeterministicDocumentParser.class);
        when(parser.parse(any(Path.class), eq(taskId.toString())))
                .thenReturn(new DocumentSnapshot(
                        taskId.toString(),
                        "parser-v1",
                        "a".repeat(64),
                        "b".repeat(64),
                        List.of(),
                        List.of(),
                        List.of()));
        DemoOperationCoordinator coordinator = new DemoOperationCoordinator();
        VerificationTaskService service = service(tasks, parser, coordinator);
        CountDownLatch managementEntered = new CountDownLatch(1);
        CountDownLatch releaseManagement = new CountDownLatch(1);
        CountDownLatch uploadStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> management = executor.submit(() -> coordinator.exclusively(() -> {
                managementEntered.countDown();
                await(releaseManagement);
                throw new IllegalStateException("模拟管理操作补偿后失败");
            }));
            assertThat(managementEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> upload = executor.submit(() -> {
                uploadStarted.countDown();
                service.upload(
                        taskId,
                        "upload-lock-request",
                        new MaterialUpload(null, null, 0, null, "说明", null));
            });
            assertThat(uploadStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);

            assertThat(storageRoot.resolve("uploads/" + taskId + "/message.txt"))
                    .doesNotExist();
            releaseManagement.countDown();
            assertThatThrownBy(() -> management.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("模拟管理操作补偿后失败");
            upload.get(5, TimeUnit.SECONDS);
            assertThat(storageRoot.resolve("uploads/" + taskId + "/message.txt"))
                    .hasContent("说明");
        } finally {
            releaseManagement.countDown();
            executor.shutdownNow();
            service.shutdownExecutor();
        }
    }

    /** 构造只运行上传路径的服务，未使用依赖均以 Mock 隔离。 */
    private VerificationTaskService service(
            VerificationTaskRepository tasks,
            DeterministicDocumentParser parser,
            DemoOperationCoordinator coordinator) {
        ObjectMapper mapper = new ObjectMapper();
        WorkbenchProperties properties = new WorkbenchProperties(
                new WorkbenchProperties.DatabaseBoundary("demo", "test", false),
                storageRoot,
                storageRoot.resolve("manifest.json"),
                storageRoot.resolve("skill-source"),
                new WorkbenchProperties.Model("", "", "", ""),
                URI.create("http://127.0.0.1"));
        return new VerificationTaskService(
                tasks,
                mock(VerificationRunRepository.class),
                mock(ClaimRepository.class),
                mock(ReleaseBindingRepository.class),
                mock(SkillVersionRepository.class),
                parser,
                mock(FactVerificationAgentRunner.class),
                properties,
                mapper,
                new CanonicalJsonHasher(mapper),
                coordinator);
    }

    /** 线程内等待测试闩锁，中断时保留标记并转换为明确失败。 */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待管理锁释放超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待管理锁释放被中断", exception);
        }
    }
}
