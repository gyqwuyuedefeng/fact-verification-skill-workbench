package com.hsmap.factverification.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.agent.FactVerificationAgentRunner;
import com.hsmap.factverification.claim.persistence.ClaimRepository;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.document.DeterministicDocumentParser;
import com.hsmap.factverification.release.persistence.ReleaseBindingRepository;
import com.hsmap.factverification.run.persistence.VerificationRunRepository;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import com.hsmap.factverification.task.VerificationTaskService;
import com.hsmap.factverification.task.persistence.VerificationTaskRepository;
import java.net.URI;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 验证 PRIMARY 与 SHADOW 共享输入条件但运行相互隔离。
 *
 * <p>测试不连接模型、MCP 或数据库，只锁定编排：正式结果先完成，影子失败不能反写正式任务。
 */
class ShadowRunIsolationTest {

    /** 同一文档、模型配置、工具合同和证据快照分别钉死 Stable 与 Candidate。 */
    @Test
    void runsCandidateInBackgroundWithoutChangingPrimaryResult() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID stableId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        VerificationTaskRepository tasks = mock(VerificationTaskRepository.class);
        VerificationRunRepository runs = mock(VerificationRunRepository.class);
        ClaimRepository claims = mock(ClaimRepository.class);
        ReleaseBindingRepository releases = mock(ReleaseBindingRepository.class);
        SkillVersionRepository skills = mock(SkillVersionRepository.class);
        FactVerificationAgentRunner agent = mock(FactVerificationAgentRunner.class);
        ObjectMapper mapper = new ObjectMapper();
        OffsetDateTime createdAt = OffsetDateTime.of(2026, 8, 12, 0, 0, 0, 0, ZoneOffset.UTC);
        VerificationTaskRepository.TaskState ready = new VerificationTaskRepository.TaskState(
                taskId,
                "task-request-001",
                "company.md",
                "a".repeat(64),
                "READY",
                false,
                snapshotId,
                "{\"blocks\":[]}",
                "b".repeat(64),
                null,
                createdAt);
        VerificationTaskRepository.TaskState completed = new VerificationTaskRepository.TaskState(
                taskId,
                ready.requestId(),
                ready.originalFileName(),
                ready.fileHash(),
                "COMPLETED",
                true,
                snapshotId,
                ready.documentSnapshotJson(),
                ready.documentSnapshotHash(),
                null,
                createdAt);
        when(tasks.findById(taskId)).thenReturn(Optional.of(ready), Optional.of(completed));
        when(runs.findByTaskAndType(taskId, "PRIMARY")).thenReturn(Optional.empty());
        when(runs.findByTaskAndType(taskId, "SHADOW")).thenReturn(Optional.empty());
        when(releases.findLatest("company-material-fact-check"))
                .thenReturn(Optional.of(new ReleaseBindingRepository.ReleaseState(
                        3,
                        "SHADOW_START",
                        stableId,
                        candidateId,
                        null,
                        true,
                        UUID.randomUUID(),
                        "{}",
                        "灰度",
                        "single-reviewer",
                        createdAt)));
        when(skills.findFrozen(stableId))
                .thenReturn(Optional.of(
                        new SkillVersionRepository.FrozenVersion(stableId, "v1", "STABLE", "c".repeat(64))));
        when(skills.findFrozen(candidateId))
                .thenReturn(Optional.of(
                        new SkillVersionRepository.FrozenVersion(candidateId, "v2", "CANDIDATE", "d".repeat(64))));
        JsonNode primaryResult = mapper.readTree("{\"claims\":[]}");
        AtomicInteger executions = new AtomicInteger();
        when(agent.run(any(), eq(snapshotId), eq("TASK"), eq(taskId), any(), any(), any()))
                .thenAnswer(invocation -> {
                    if (executions.getAndIncrement() == 0) {
                        return primaryResult;
                    }
                    throw new IllegalStateException("影子模型失败");
                });

        VerificationTaskService service = new VerificationTaskService(
                tasks,
                runs,
                claims,
                releases,
                skills,
                mock(DeterministicDocumentParser.class),
                agent,
                properties(),
                mapper,
                new CanonicalJsonHasher(mapper));

        var result = service.start(taskId, "run-request-001", true);

        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(agent, timeout(2_000).times(2)).run(any(), eq(snapshotId), eq("TASK"), eq(taskId), any(), any(), any());
        verify(tasks).transition(eq(taskId), eq("RUNNING"), eq("COMPLETED"), any());
        verify(runs, timeout(2_000)).fail(any(), eq("SHADOW_EXECUTION_FAILED"), any(), any());
    }

    private static WorkbenchProperties properties() {
        return new WorkbenchProperties(
                new WorkbenchProperties.DatabaseBoundary("kjjr_inx_brain", "test", false),
                Path.of("target/test-shadow"),
                Path.of("../evals/manifest.json"),
                Path.of("../skills/company-material-fact-check"),
                new WorkbenchProperties.Model("http://model.invalid", "/v1/chat/completions", "company-qwen", ""),
                URI.create("http://127.0.0.1:19091/mcp"));
    }
}
