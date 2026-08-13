package com.hsmap.factverification.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.agent.AgentBusinessEvent;
import com.hsmap.factverification.agent.AgentOutputContract;
import com.hsmap.factverification.agent.AgentRuntimeParameters;
import com.hsmap.factverification.agent.AgentVariant;
import com.hsmap.factverification.agent.FactVerificationAgentRunner;
import com.hsmap.factverification.claim.persistence.ClaimRepository;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.document.DeterministicDocumentParser;
import com.hsmap.factverification.document.DocumentSnapshot;
import com.hsmap.factverification.release.InitialStableBootstrapService;
import com.hsmap.factverification.release.persistence.ReleaseBindingRepository;
import com.hsmap.factverification.run.persistence.VerificationRunRepository;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import com.hsmap.factverification.shared.ErrorSanitizer;
import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import com.hsmap.factverification.task.persistence.VerificationTaskRepository;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单人 MVP 的材料任务编排：保存、确定性解析、读取 Stable、执行 PRIMARY 并落库。
 *
 * <p>没有消息队列或通用调度器；HTTP 调用完成解析并异步启动单实例 Agent，数据库唯一键和状态条件提供最小重试保护。
 */
@Service
public class VerificationTaskService implements VerificationTaskUseCase {

    private static final int MAX_MODEL_SNAPSHOT_CHARS = 200_000;

    private final VerificationTaskRepository tasks;
    private final VerificationRunRepository runs;
    private final ClaimRepository claims;
    private final ReleaseBindingRepository releases;
    private final SkillVersionRepository skillVersions;
    private final DeterministicDocumentParser parser;
    private final FactVerificationAgentRunner agentRunner;
    private final WorkbenchProperties properties;
    private final ObjectMapper objectMapper;
    private final CanonicalJsonHasher hasher;
    private final ExecutorService primaryExecutor;
    private final Map<UUID, List<RunEventView>> events = new ConcurrentHashMap<>();

    public VerificationTaskService(
            VerificationTaskRepository tasks,
            VerificationRunRepository runs,
            ClaimRepository claims,
            ReleaseBindingRepository releases,
            SkillVersionRepository skillVersions,
            DeterministicDocumentParser parser,
            FactVerificationAgentRunner agentRunner,
            WorkbenchProperties properties,
            ObjectMapper objectMapper,
            CanonicalJsonHasher hasher) {
        this.tasks = tasks;
        this.runs = runs;
        this.claims = claims;
        this.releases = releases;
        this.skillVersions = skillVersions;
        this.parser = parser;
        this.agentRunner = agentRunner;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.hasher = hasher;
        this.primaryExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread worker = new Thread(runnable, "fact-verification-primary");
            worker.setDaemon(true);
            return worker;
        });
    }

    /** 创建上传槽；重复 requestId 返回原任务。占位元数据会在第一次上传时原子替换。 */
    @Override
    @Transactional
    public VerificationTaskView create(String requestId) {
        return tasks.findByRequestId(requestId).map(this::toView).orElseGet(() -> {
            UUID taskId = UUID.randomUUID();
            OffsetDateTime now = now();
            Path placeholder = uploadDirectory(taskId).resolve("pending-upload");
            tasks.insert(new VerificationTaskRepository.NewTask(
                    taskId,
                    requestId,
                    "pending-upload",
                    "application/octet-stream",
                    1,
                    "0".repeat(64),
                    placeholder.toString(),
                    false,
                    now));
            return toView(requiredTask(taskId));
        });
    }

    /** 文件先写入受控 task 目录并解析成功，再固定数据库文档快照。 */
    @Override
    @Transactional
    public VerificationTaskView upload(UUID taskId, String requestId, MaterialUpload material) {
        VerificationTaskRepository.TaskState current = requiredTask(taskId);
        if ("READY".equals(current.status())) {
            return toView(current);
        }
        if (!"UPLOADED".equals(current.status())) {
            throw new ServiceException("TASK_NOT_UPLOADABLE", "当前任务状态不允许上传材料");
        }
        String message = material.safeMessage();
        boolean hasFile = material.content() != null && material.size() > 0;
        if (!hasFile && message.isBlank()) {
            throw new ServiceException("DOCUMENT_EMPTY", "请输入文字或上传文件");
        }
        if (message.length() > 20_000) {
            throw new ServiceException("MESSAGE_TOO_LONG", "输入文字超过 20000 字符上限");
        }
        String inputType = hasFile ? (message.isBlank() ? "FILE" : "COMBINED") : "TEXT";
        String safeName = hasFile ? safeFileName(material.originalFileName()) : "message.txt";
        Path target = uploadDirectory(taskId).resolve(safeName).normalize();
        ensureWithin(target, uploadDirectory(taskId));
        try {
            Files.createDirectories(target.getParent());
            if (hasFile) {
                try (InputStream input = material.content().open()) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.writeString(target, message, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new ServiceException("DOCUMENT_STORE_FAILED", "上传材料保存失败");
        }
        DocumentSnapshot snapshot = parser.parse(target, taskId.toString());
        String snapshotJson = writeJson(snapshot);
        if (snapshotJson.length() > MAX_MODEL_SNAPSHOT_CHARS) {
            throw new ServiceException("DOCUMENT_SNAPSHOT_TOO_LARGE", "解析结果超过单次核验上限");
        }
        OffsetDateTime now = now();
        if (tasks.attachMaterial(
                        taskId,
                        safeName,
                        hasFile ? fallbackMediaType(material.mediaType()) : "text/plain",
                        hasFile ? material.size() : message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                        snapshot.fileHash(),
                        target.toString(),
                        message.isBlank() ? null : message,
                        inputType,
                        now)
                != 1) {
            throw new ServiceException("TASK_STATE_CHANGED", "任务状态已变化，请刷新后重试");
        }
        UUID evidenceSnapshotId = UUID.randomUUID();
        if (tasks.markReady(
                        taskId,
                        snapshot.parserVersion(),
                        snapshotJson,
                        snapshot.snapshotHash(),
                        evidenceSnapshotId,
                        now)
                != 1) {
            throw new ServiceException("TASK_STATE_CHANGED", "文档快照固定失败");
        }
        return toView(requiredTask(taskId));
    }

    /**
     * 读取当前发布状态并立即钉死 PRIMARY/可选 SHADOW 的全部条件。
     *
     * <p>PRIMARY 在当前请求内完成并成为唯一正式结果；SHADOW 只在 PRIMARY 成功后启动后台线程，任何影子异常只写自身运行记录。
     */
    @Override
    @Transactional
    public VerificationTaskView start(UUID taskId, String requestId, String executionMode) {
        VerificationTaskRepository.TaskState task = requiredTask(taskId);
        var existing = runs.findByTaskAndType(taskId, "PRIMARY");
        if (existing.isPresent()) {
            return toView(requiredTask(taskId));
        }
        if (!"READY".equals(task.status())) {
            throw new ServiceException("TASK_NOT_READY", "材料尚未完成确定性解析");
        }
        if (!"BASELINE".equals(executionMode) && !"STABLE".equals(executionMode)) {
            throw new ServiceException("EXECUTION_MODE_INVALID", "普通核验只支持 BASELINE 或当前 Stable");
        }
        ReleaseBindingRepository.ReleaseState release = null;
        SkillVersionRepository.FrozenVersion stableVersion = null;
        SkillVersionRepository.FrozenVersion candidateVersion = null;
        AgentVariant primaryVariant;
        UUID stableVersionId = null;
        if ("BASELINE".equals(executionMode)) {
            primaryVariant = AgentVariant.baseline(hasher.hash(AgentVariant.BASELINE_INSTRUCTION));
        } else {
            release = releases.findLatest(InitialStableBootstrapService.SKILL_KEY)
                    .orElseThrow(() -> new ServiceException("STABLE_VERSION_MISSING", "尚未建立初始 Stable"));
            stableVersionId = release.stableVersionId();
            stableVersion = skillVersions
                    .findFrozen(stableVersionId)
                    .orElseThrow(() -> new ServiceException("STABLE_VERSION_INVALID", "Stable 版本不存在或未冻结"));
            primaryVariant = skillVariant(stableVersion);
            candidateVersion = release.shadowEnabled() && release.candidateVersionId() != null
                    ? requiredShadowVersion(release)
                    : null;
        }
        UUID primaryRunId = UUID.randomUUID();
        UUID shadowRunId = candidateVersion == null ? null : UUID.randomUUID();
        OffsetDateTime startedAt = now();
        runs.insert(new VerificationRunRepository.NewRun(
                primaryRunId,
                taskId,
                "PRIMARY",
                primaryVariant.type(),
                stableVersionId,
                modelConfigHash(),
                hasher.hash("mcp-tools-v1"),
                hasher.hash("verification-result-v1"),
                task.evidenceSnapshotId(),
                startedAt));
        if (candidateVersion != null) {
            runs.insert(new VerificationRunRepository.NewRun(
                    shadowRunId,
                    taskId,
                    "SHADOW",
                    "SKILL",
                    candidateVersion.id(),
                    modelConfigHash(),
                    hasher.hash("mcp-tools-v1"),
                    hasher.hash("verification-result-v1"),
                    task.evidenceSnapshotId(),
                    startedAt));
            tasks.markShadowRequested(taskId, startedAt);
        }
        tasks.transition(taskId, "READY", "RUNNING", startedAt);
        List<RunEventView> runEvents = new CopyOnWriteArrayList<>();
        runEvents.add(new RunEventView(
                "1", "RUN_CREATED", Map.of("runId", primaryRunId.toString(), "executionMode", executionMode)));
        events.put(primaryRunId, runEvents);
        AgentVariant lockedPrimaryVariant = primaryVariant;
        SkillVersionRepository.FrozenVersion lockedCandidate = candidateVersion;
        primaryExecutor.execute(
                () -> runPrimary(primaryRunId, shadowRunId, task, lockedPrimaryVariant, lockedCandidate, startedAt));
        return toView(requiredTask(taskId));
    }

    /**
     * PRIMARY 在单线程执行器内完成，启动 HTTP 可以先返回 runId 供页面订阅事件。
     *
     * <p>后台边界必须先把任何未检查失败收口到数据库，再决定是否把 JVM Error 继续抛给执行器。这样即使依赖链接错误发生在
     * Agent/MCP 初始化阶段，页面也会看到 FAILED，而不是永久停留在 RUNNING；Error 仍会重新抛出，避免吞掉 JVM 级致命信号。
     */
    private void runPrimary(
            UUID primaryRunId,
            UUID shadowRunId,
            VerificationTaskRepository.TaskState task,
            AgentVariant primaryVariant,
            SkillVersionRepository.FrozenVersion candidateVersion,
            OffsetDateTime startedAt) {
        try {
            runs.markRunning(primaryRunId, startedAt);
            executeRun(primaryRunId, task, primaryVariant, startedAt);
            OffsetDateTime finishedAt = now();
            tasks.transition(task.id(), "RUNNING", "COMPLETED", finishedAt);
            if (candidateVersion != null) {
                launchShadow(shadowRunId, task, skillVariant(candidateVersion));
            }
        } catch (Throwable failure) {
            OffsetDateTime failedAt = now();
            runs.fail(
                    primaryRunId,
                    "PRIMARY_EXECUTION_FAILED",
                    ErrorSanitizer.sanitize(failure.getMessage()),
                    failedAt);
            tasks.transition(task.id(), "RUNNING", "FAILED", failedAt);
            events.computeIfAbsent(primaryRunId, ignored -> new CopyOnWriteArrayList<>())
                    .add(new RunEventView(
                            String.valueOf(events.get(primaryRunId).size() + 1),
                            "RUN_FAILED",
                            Map.of("runId", primaryRunId.toString(), "errorCode", "PRIMARY_EXECUTION_FAILED")));
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    /** 旧单元测试桥接；生产控制器不再暴露 includeShadow。 */
    @Deprecated
    public VerificationTaskView start(UUID taskId, String requestId, boolean includeShadow) {
        return start(taskId, requestId, "STABLE");
    }

    /** 只在发布开关已开启且存在 Candidate 时允许请求影子运行。 */
    private SkillVersionRepository.FrozenVersion requiredShadowVersion(ReleaseBindingRepository.ReleaseState release) {
        if (!release.shadowEnabled() || release.candidateVersionId() == null) {
            throw new ServiceException("SHADOW_NOT_AVAILABLE", "影子灰度需先注册 Candidate 并开启");
        }
        return skillVersions
                .findFrozen(release.candidateVersionId())
                .filter(version -> "CANDIDATE".equals(version.status()))
                .orElseThrow(() -> new ServiceException("CANDIDATE_VERSION_INVALID", "Candidate 版本不存在或未冻结"));
    }

    /**
     * 使用最小 daemon 线程执行一个影子任务，不引入消息队列或通用调度平台。
     *
     * <p>影子失败必须与 PRIMARY 一样先持久化终态；JVM Error 在记录失败后继续抛出，保证后台观察不会悄悄掩盖运行环境损坏。
     */
    private void launchShadow(UUID runId, VerificationTaskRepository.TaskState task, AgentVariant variant) {
        Thread worker = new Thread(
                () -> {
                    OffsetDateTime startedAt = now();
                    try {
                        runs.markRunning(runId, startedAt);
                        executeRun(runId, task, variant, startedAt);
                    } catch (Throwable failure) {
                        runs.fail(
                                runId,
                                "SHADOW_EXECUTION_FAILED",
                                ErrorSanitizer.sanitize(failure.getMessage()),
                                now());
                        if (failure instanceof Error error) {
                            throw error;
                        }
                    }
                },
                "fact-verification-shadow-" + runId);
        worker.setDaemon(true);
        worker.start();
    }

    /** PRIMARY 与 SHADOW 共用完全相同的执行函数，仅 runId 与冻结 Skill 版本不同。 */
    private void executeRun(
            UUID runId, VerificationTaskRepository.TaskState task, AgentVariant variant, OffsetDateTime startedAt) {
        List<RunEventView> runEvents = events.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>());
        JsonNode result = agentRunner.run(
                runId,
                task.evidenceSnapshotId(),
                "TASK",
                task.id(),
                variant,
                prompt(runId, task, variant),
                event -> appendEvent(runId, runEvents, event));
        persistClaims(runId, result.path("claims"));
        OffsetDateTime finishedAt = now();
        runs.complete(
                runId,
                writeJson(result),
                "[]",
                "{}",
                Duration.between(startedAt, finishedAt).toMillis(),
                finishedAt);
        runEvents.add(new RunEventView(
                String.valueOf(runEvents.size() + 1), "RUN_COMPLETED", Map.of("runId", runId.toString())));
    }

    @Override
    public VerificationTaskView findTask(UUID taskId) {
        return toView(requiredTask(taskId));
    }

    @Override
    public List<VerificationClaimView> findPrimaryClaims(UUID taskId) {
        UUID runId = runs.findByTaskAndType(taskId, "PRIMARY")
                .map(VerificationRunRepository.RunState::id)
                .orElseThrow(() -> new ServiceException("PRIMARY_RUN_NOT_FOUND", "正式运行不存在"));
        return findRunClaims(runId);
    }

    @Override
    public List<VerificationClaimView> findRunClaims(UUID runId) {
        runs.findById(runId).orElseThrow(() -> new ServiceException("RUN_NOT_FOUND", "运行不存在"));
        return claims.findByRun(runId).stream().map(this::toClaimView).toList();
    }

    /** 重启后至少根据已持久化运行状态恢复终态事件，过程事件只用于当前页面进度。 */
    @Override
    public List<RunEventView> replayEvents(UUID runId, String lastEventId) {
        List<RunEventView> current = events.get(runId);
        if (current == null) {
            VerificationRunRepository.RunState run =
                    runs.findById(runId).orElseThrow(() -> new ServiceException("RUN_NOT_FOUND", "运行不存在"));
            current = List.of(new RunEventView(
                    "1",
                    "COMPLETED".equals(run.status()) ? "RUN_COMPLETED" : "RUN_STATUS",
                    Map.of("runId", runId.toString(), "status", run.status())));
        }
        long after = parseEventId(lastEventId);
        return current.stream()
                .filter(event -> Long.parseLong(event.id()) > after)
                .toList();
    }

    private void persistClaims(UUID runId, JsonNode claimNodes) {
        int ordinal = 0;
        for (JsonNode claim : claimNodes) {
            Map<String, Object> subject = mapOrEmpty(claim.path("subject"));
            claims.append(new ClaimRepository.ClaimRow(
                    UUID.randomUUID(),
                    runId,
                    ordinal++,
                    claim.path("claimText").asText(),
                    writeJson(claim.path("materialLocator")),
                    writeJson(claim.path("normalizedClaim")),
                    stringOrNull(subject.get("companyId")),
                    stringOrNull(subject.get("companyName")),
                    claim.path("status").asText(),
                    writeJson(claim.path("riskFlags")),
                    writeJson(claim.path("evidence")),
                    claim.path("explanation").asText(),
                    claim.path("requiresHumanIntervention").asBoolean(),
                    now()));
        }
    }

    private VerificationTaskView toView(VerificationTaskRepository.TaskState task) {
        UUID primaryRun = runs.findByTaskAndType(task.id(), "PRIMARY")
                .map(VerificationRunRepository.RunState::id)
                .orElse(null);
        String executionMode = runs.findByTaskAndType(task.id(), "PRIMARY")
                .map(run -> "BASELINE".equals(run.variantType()) ? "BASELINE" : "STABLE")
                .orElse(null);
        String fileName = "pending-upload".equals(task.originalFileName()) ? null : task.originalFileName();
        String fileHash = "0".repeat(64).equals(task.fileHash()) ? null : task.fileHash();
        return new VerificationTaskView(
                task.id(),
                task.inputType(),
                task.userMessage() != null && !task.userMessage().isBlank(),
                executionMode,
                fileName,
                fileHash,
                task.documentSnapshotHash(),
                task.status(),
                primaryRun,
                task.errorCode(),
                task.createdAt());
    }

    private VerificationClaimView toClaimView(ClaimRepository.ClaimView claim) {
        Map<String, Object> subject = new LinkedHashMap<>();
        if (claim.companyId() != null) {
            subject.put("companyId", claim.companyId());
        }
        if (claim.companyName() != null) {
            subject.put("companyName", claim.companyName());
        }
        return new VerificationClaimView(
                claim.id(),
                claim.claimText(),
                readMap(claim.materialLocatorJson()),
                readMap(claim.normalizedClaimJson()),
                subject.isEmpty() ? null : subject,
                claim.verificationStatus(),
                readList(claim.riskFlagsJson(), new TypeReference<List<String>>() {}),
                readList(claim.evidenceJson(), new TypeReference<List<Map<String, Object>>>() {}),
                claim.explanation(),
                claim.requiresHumanIntervention());
    }

    private String prompt(UUID runId, VerificationTaskRepository.TaskState task, AgentVariant variant) {
        return """
                核验以下确定性文档快照。只使用已注册的六个企业证据工具。
                %s
                运行元数据：
                {"runId":"%s","variant":{"type":"%s","identifier":"%s","contentHash":"%s"},
                "documentSnapshotHash":"%s","evidenceSnapshotId":"%s"}
                文档快照：
                %s
                用户补充说明：%s
                """
                .formatted(
                        AgentOutputContract.instruction(),
                        runId,
                        variant.type(),
                        variant.identifier(),
                        variant.contentHash(),
                        task.documentSnapshotHash(),
                        task.evidenceSnapshotId(),
                        task.documentSnapshotJson(),
                        "COMBINED".equals(task.inputType()) && task.userMessage() != null ? task.userMessage() : "");
    }

    /** 冻结版本必须从只读运行目录加载，普通页永远不能覆盖该目录。 */
    private AgentVariant skillVariant(SkillVersionRepository.FrozenVersion version) {
        return AgentVariant.skill(
                version.version(),
                version.contentHash(),
                properties
                        .storageRoot()
                        .resolve("skill-runtime")
                        .resolve(version.id().toString()));
    }

    private void appendEvent(UUID runId, List<RunEventView> target, AgentBusinessEvent event) {
        Map<String, Object> data = new LinkedHashMap<>(event.payload());
        data.put("runId", runId.toString());
        target.add(new RunEventView(String.valueOf(target.size() + 1), event.type(), data));
    }

    private VerificationTaskRepository.TaskState requiredTask(UUID taskId) {
        return tasks.findById(taskId).orElseThrow(() -> new ServiceException("TASK_NOT_FOUND", "核验任务不存在"));
    }

    private Path uploadDirectory(UUID taskId) {
        return properties
                .storageRoot()
                .toAbsolutePath()
                .normalize()
                .resolve("uploads")
                .resolve(taskId.toString());
    }

    private static String safeFileName(String original) {
        if (original == null || original.isBlank()) {
            throw new ServiceException("FILE_NAME_REQUIRED", "上传文件名不能为空");
        }
        String normalized = original.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1).strip();
        if (fileName.isBlank() || fileName.equals(".") || fileName.equals("..")) {
            throw new ServiceException("FILE_NAME_INVALID", "上传文件名无效");
        }
        return fileName;
    }

    private static void ensureWithin(Path target, Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!target.toAbsolutePath().normalize().startsWith(normalizedRoot)) {
            throw new ServiceException("UPLOAD_PATH_INVALID", "上传路径越过任务数据目录");
        }
    }

    private String modelConfigHash() {
        return hasher.hash(Map.of(
                "url", properties.model().url(),
                "endpointPath", properties.model().endpointPath(),
                "id", properties.model().id(),
                "parameters", AgentRuntimeParameters.manifestParameters()));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new ServiceException("JSON_SERIALIZATION_FAILED", "工作台数据序列化失败");
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new ServiceException("PERSISTED_JSON_INVALID", "已保存主张格式无效");
        }
    }

    private <T> T readList(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new ServiceException("PERSISTED_JSON_INVALID", "已保存主张格式无效");
        }
    }

    private Map<String, Object> mapOrEmpty(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Collections.emptyMap();
        }
        return objectMapper.convertValue(node, new TypeReference<>() {});
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String fallbackMediaType(String mediaType) {
        return mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType;
    }

    private static long parseEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(lastEventId));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** 关闭单实例后台执行器，避免测试启动/关闭后遗留应用线程。 */
    @PreDestroy
    void shutdownExecutor() {
        primaryExecutor.shutdownNow();
    }
}
