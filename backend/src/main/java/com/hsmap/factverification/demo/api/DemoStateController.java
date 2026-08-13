package com.hsmap.factverification.demo.api;

import com.hsmap.factverification.demo.DemoStateService;
import com.hsmap.factverification.demo.DemoStateView;
import com.hsmap.factverification.demo.BuiltinDemoFixtureService;
import com.hsmap.factverification.demo.SnapshotArchiveService;
import com.hsmap.factverification.demo.SkillPresetService;
import com.hsmap.factverification.demo.StaleRecoveryView;
import com.hsmap.factverification.shared.RequestId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 仅供比赛 test profile 使用的演示状态管理 API。
 *
 * <p>Profile 与显式开关必须同时满足才会装配，防止生产环境因配置误用暴露完整清空入口。
 */
@RestController
@Profile("test")
@ConditionalOnProperty(prefix = "workbench.demo-admin", name = "enabled", havingValue = "true")
@RequestMapping("/api/admin/demo-state")
public class DemoStateController {

    private final DemoStateService service;
    private final SnapshotArchiveService snapshots;
    private final SkillPresetService presets;
    private final BuiltinDemoFixtureService builtinFixture;

    /** 注入演示状态、快照和固定 fixture 服务；控制器只处理 HTTP 合同，不直接访问 JDBC 或文件系统。 */
    public DemoStateController(
            DemoStateService service,
            SnapshotArchiveService snapshots,
            SkillPresetService presets,
            BuiltinDemoFixtureService builtinFixture) {
        this.service = service;
        this.snapshots = snapshots;
        this.presets = presets;
        this.builtinFixture = builtinFixture;
    }

    /** 返回七张业务表计数和三个受管运行目录是否为空的脱敏状态。 */
    @GetMapping("/status")
    public DemoStateView status() {
        return service.status();
    }

    /**
     * 用幂等键记录调用边界，并把固定确认短语交由服务层检查后执行完整清空。
     *
     * <p>当前 MVP 不保存管理端操作日志；RequestId 校验仍防止空值或不可见字符绕过 HTTP 合同。
     */
    @PostMapping("/reset")
    public DemoStateView reset(
            @RequestHeader("Idempotency-Key") String requestId, @Valid @RequestBody ResetCommand command) {
        RequestId.requireValid(requestId);
        return service.reset(requestId, command.confirmationPhrase());
    }

    /**
     * 回收一小时以上且 worker 从未启动的固定遗留核验模式。
     *
     * <p>HTTP 层只接受精确确认头，不接受任务 ID、运行 ID 或阈值；全部候选条件由服务事务内的固定 SQL 决定。
     */
    @PostMapping("/recover-stale")
    public StaleRecoveryView recoverStale(@RequestHeader("X-Confirmation-Phrase") String confirmationPhrase) {
        return service.recoverStale(exactUtf8HeaderPhrase(confirmationPhrase, "回收遗留任务"));
    }

    /**
     * 流式下载当前静止比赛状态，文件名只含 UTC 日期，不暴露 storageRoot 或数据库身份。
     *
     * <p>StreamingResponseBody 的写入回调由快照服务先执行活动状态保护，再按行/按文件写 ZIP。
     */
    @GetMapping(value = "/export", produces = "application/zip")
    public ResponseEntity<StreamingResponseBody> exportSnapshot() {
        String date = LocalDate.now(ZoneOffset.UTC).toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=workbench-state-" + date + ".zip")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(output -> snapshots.exportTo(output));
    }

    /**
     * 接收原始 ZIP 请求流并交给服务边读边执行 200 MB 上限，不复用普通材料 multipart 配置。
     *
     * <p>X-Confirmation-Phrase 由服务层按“导入快照”精确校验；成功后返回既有脱敏状态投影。
     */
    @PostMapping(value = "/import", consumes = "application/zip")
    public DemoStateView importSnapshot(
            @RequestHeader("X-Confirmation-Phrase") String confirmationPhrase, HttpServletRequest request)
            throws IOException {
        snapshots.importFrom(request.getInputStream(), exactUtf8HeaderPhrase(confirmationPhrase, "导入快照"));
        return service.status();
    }

    /** 返回三套完整 Skill Markdown/references；业务顺序由预置服务固定，HTTP 不接受任意路径或 preset id。 */
    @GetMapping("/skill-presets")
    public java.util.List<SkillPresetService.SkillPreset> skillPresets() {
        return presets.presets();
    }

    /** 使用专用确认短语从空状态恢复固定脱敏 fixture，并返回 Task 4 既有状态投影。 */
    @PostMapping("/import-builtin")
    public DemoStateView importBuiltin(@RequestHeader("X-Confirmation-Phrase") String confirmationPhrase) {
        builtinFixture.importBuiltin(exactUtf8HeaderPhrase(confirmationPhrase, "导入内置演示数据"));
        return service.status();
    }

    /**
     * 还原 Servlet 按 ISO-8859-1 投影的固定 UTF-8 中文确认头。
     *
     * <p>仅当接收值逐字等于某条已知短语的 UTF-8 原始字节投影时才还原；其他值保持不变并由服务层拒绝，不能借此解码任意输入。
     */
    private static String exactUtf8HeaderPhrase(String received, String expected) {
        if (expected.equals(received)) {
            return received;
        }
        String servletProjection = new String(expected.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        return servletProjection.equals(received) ? expected : received;
    }

    /** 管理端重置正文只承载显式确认语，避免接受表名、目录名或任意清理选项。 */
    public record ResetCommand(@NotBlank String confirmationPhrase) {}
}
