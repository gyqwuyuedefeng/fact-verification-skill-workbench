package com.hsmap.factverification.demo;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hsmap.factverification.demo.api.DemoStateController;
import com.hsmap.factverification.shared.ApiExceptionHandler;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 被测试对象：DemoStateController 的 test-profile 条件装配和 HTTP 合同。
 * 测试目的：确保仅测试环境显式开启后才暴露比赛数据管理端点，且清空请求保留幂等键与确认短语边界。
 * 覆盖范围：GET/POST 路径、请求转发、非 test profile 与开关关闭时的 Bean 缺失。
 * 前置条件：HTTP 合同使用独立 MockMvc，条件装配使用轻量 ApplicationContextRunner，不连接真实数据库。
 */
class DemoStateApiTest {

    private DemoStateService service;
    private MockMvc mvc;

    /** 初始化控制器的独立 HTTP 测试环境，避免条件装配测试依赖完整 Web 应用。 */
    @BeforeEach
    void setUp() {
        service = mock(DemoStateService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DemoStateController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /**
     * 测试场景：测试环境管理端查询状态并提交带正确确认语的清空请求。
     * 前置条件：请求包含 Idempotency-Key，服务由 Mock 隔离为不触碰实际比赛数据。
     * 期望结果：状态查询和清空均返回成功，确认短语原样传递至应用服务。
     * 断言重点：API 路径、JSON 正文与幂等请求头满足比赛演示脚本可调用的固定合同。
     */
    @Test
    void exposesStatusAndResetEndpoints() throws Exception {
        org.mockito.Mockito.when(service.status()).thenReturn(new DemoStateView(Map.of(), Map.of()));

        mvc.perform(get("/api/admin/demo-state/status")).andExpect(status().isOk());
        mvc.perform(post("/api/admin/demo-state/reset")
                        .header("Idempotency-Key", "demo-reset-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmationPhrase\":\"清空全部比赛数据\"}"))
                .andExpect(status().isOk());

        verify(service).reset(eq("清空全部比赛数据"));
    }

    /**
     * 测试场景：非 test profile 即使错误地开启配置，也尝试加载演示管理控制器。
     * 前置条件：上下文只注册控制器及其服务 Mock，不创建业务基础设施。
     * 期望结果：Profile 条件阻止控制器 Bean 装配。
     * 断言重点：生产或开发环境不能仅凭一个配置开关暴露清空接口。
     */
    @Test
    void doesNotAssembleControllerOutsideTestProfile() {
        contextRunner("prod", true)
                .run(context -> org.assertj.core.api.Assertions.assertThat(context)
                        .doesNotHaveBean(DemoStateController.class));
    }

    /**
     * 测试场景：test profile 未显式开启演示管理开关。
     * 前置条件：上下文激活 test，但保留默认关闭的 demo-admin.enabled。
     * 期望结果：控制器 Bean 不装配。
     * 断言重点：测试 profile 本身不应自动暴露不可逆清空入口。
     */
    @Test
    void doesNotAssembleControllerWhenFeatureSwitchIsDisabled() {
        contextRunner("test", false)
                .run(context -> org.assertj.core.api.Assertions.assertThat(context)
                        .doesNotHaveBean(DemoStateController.class));
    }

    /** 为条件测试构造最小上下文，显式提供控制器的唯一构造器依赖。 */
    private static ApplicationContextRunner contextRunner(String profile, boolean enabled) {
        return new ApplicationContextRunner()
                .withUserConfiguration(DemoStateController.class)
                .withBean(DemoStateService.class, () -> mock(DemoStateService.class))
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withPropertyValues("workbench.demo-admin.enabled=" + enabled);
    }
}
