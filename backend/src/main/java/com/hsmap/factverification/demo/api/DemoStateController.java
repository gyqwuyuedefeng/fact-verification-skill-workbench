package com.hsmap.factverification.demo.api;

import com.hsmap.factverification.demo.DemoStateService;
import com.hsmap.factverification.demo.DemoStateView;
import com.hsmap.factverification.shared.RequestId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    /** 注入演示状态服务；控制器不直接访问 JDBC 或文件系统。 */
    public DemoStateController(DemoStateService service) {
        this.service = service;
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
        return service.reset(command.confirmationPhrase());
    }

    /** 管理端重置正文只承载显式确认语，避免接受表名、目录名或任意清理选项。 */
    public record ResetCommand(@NotBlank String confirmationPhrase) {}
}
