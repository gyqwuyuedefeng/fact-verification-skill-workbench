package com.hsmap.factverification.demo;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hsmap.factverification.demo.api.DemoStateController;
import com.hsmap.factverification.shared.ApiExceptionHandler;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 被测试对象：DemoStateController 的 test-profile 条件装配和 HTTP 合同。
 * 测试目的：确保仅测试环境显式开启后才暴露比赛数据管理端点，且清空请求保留幂等键与确认短语边界。
 * 覆盖范围：GET/POST 路径、请求转发、非 test profile 与开关关闭时的 Bean 缺失。
 * 前置条件：HTTP 合同使用独立 MockMvc，条件装配使用轻量 ApplicationContextRunner，不连接真实数据库。
 */
class DemoStateApiTest {

    private DemoStateService service;
    private SnapshotArchiveService snapshots;
    private SkillPresetService presets;
    private BuiltinDemoFixtureService builtinFixture;
    private DemoImportIdempotency importIdempotency;
    private MockMvc mvc;

    /** 初始化控制器的独立 HTTP 测试环境，避免条件装配测试依赖完整 Web 应用。 */
    @BeforeEach
    void setUp() {
        service = mock(DemoStateService.class);
        snapshots = mock(SnapshotArchiveService.class);
        presets = mock(SkillPresetService.class);
        builtinFixture = mock(BuiltinDemoFixtureService.class);
        importIdempotency = new DemoImportIdempotency();
        org.mockito.Mockito.doAnswer(invocation -> {
                    String phrase = invocation.getArgument(0);
                    if (!"导入快照".equals(phrase)) {
                        throw new com.hsmap.factverification.shared.ServiceException(
                                "DEMO_SNAPSHOT_CONFIRMATION_INVALID", "确认短语必须为“导入快照”");
                    }
                    return null;
                })
                .when(service)
                .requireImportConfirmationPhrase(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(invocation -> {
                    String phrase = invocation.getArgument(0);
                    if (!"导入内置演示数据".equals(phrase)) {
                        throw new com.hsmap.factverification.shared.ServiceException(
                                "DEMO_BUILTIN_CONFIRMATION_INVALID", "确认短语必须为“导入内置演示数据”");
                    }
                    return null;
                })
                .when(service)
                .requireBuiltinImportConfirmationPhrase(org.mockito.ArgumentMatchers.anyString());
        mvc = MockMvcBuilders.standaloneSetup(
                        new DemoStateController(service, snapshots, presets, builtinFixture, importIdempotency))
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

        verify(service).reset(eq("demo-reset-001"), eq("清空全部比赛数据"));
    }

    /**
     * 测试场景：test-only 管理端提交固定确认头回收遗留核验。
     * 前置条件：服务返回零条恢复结果及既有脱敏状态投影。
     * 期望结果：POST 返回恢复数量和 status，确认短语逐字交给服务层验证。
     * 断言重点：端点不接受任务 ID、运行 ID、超时阈值或请求正文。
     */
    @Test
    void exposesFixedStaleRecoveryEndpoint() throws Exception {
        org.mockito.Mockito.when(service.recoverStale("回收遗留任务"))
                .thenReturn(new StaleRecoveryView(0, 0, new DemoStateView(Map.of(), Map.of())));

        mvc.perform(post("/api/admin/demo-state/recover-stale")
                        .header("X-Confirmation-Phrase", "回收遗留任务"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.recoveredTasks")
                        .value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.recoveredRuns")
                        .value(0));

        verify(service).recoverStale("回收遗留任务");
    }

    /**
     * 测试场景：真实 HTTP 连接把中文确认头的 UTF-8 字节按 ISO-8859-1 投影给 Servlet。
     * 前置条件：请求字节仍精确等于“回收遗留任务”的 UTF-8，不接受 URL 编码或近似文本。
     * 期望结果：控制器只将这组固定字节还原为业务短语，服务层继续执行逐字安全校验。
     * 断言重点：兼容仅发生在 HTTP 适配边界，不能放宽服务确认语或接受调用方自定义值。
     */
    @Test
    void restoresExactUtf8ConfirmationPhraseFromServletHeaderBytes() throws Exception {
        String servletHeader = new String(
                "回收遗留任务".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        org.mockito.Mockito.when(service.recoverStale("回收遗留任务"))
                .thenReturn(new StaleRecoveryView(0, 0, new DemoStateView(Map.of(), Map.of())));

        mvc.perform(post("/api/admin/demo-state/recover-stale")
                        .header("X-Confirmation-Phrase", servletHeader))
                .andExpect(status().isOk());

        verify(service).recoverStale("回收遗留任务");
    }

    /**
     * 测试场景：管理端流式下载快照并以原始 application/zip 请求体导入。
     * 前置条件：导出服务向响应流写入少量 ZIP 标识字节，导入携带固定 X-Confirmation-Phrase。
     * 期望结果：下载响应含附件文件名和 ZIP 媒体类型，上传字节与确认语原样交给快照服务。
     * 断言重点：接口不使用 multipart，且仍位于 Task 4 双条件控制器内。
     */
    @Test
    void exposesStreamingExportAndRawZipImportEndpoints() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
                    java.io.OutputStream output = invocation.getArgument(0);
                    output.write("zip".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return null;
                })
                .when(snapshots)
                .exportTo(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.when(service.status()).thenReturn(new DemoStateView(Map.of(), Map.of()));

        MvcResult export = mvc.perform(get("/api/admin/demo-state/export"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(export))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string(
                                "Content-Disposition", org.hamcrest.Matchers.containsString("workbench-state-")))
                .andExpect(content().bytes("zip".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        byte[] requestBody = "raw-zip".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(post("/api/admin/demo-state/import")
                        .header("Idempotency-Key", "demo-import-001")
                        .header("X-Confirmation-Phrase", "导入快照")
                        .contentType("application/zip")
                        .content(requestBody))
                .andExpect(status().isOk());

        verify(snapshots).importFrom(org.mockito.ArgumentMatchers.any(), eq("导入快照"));
    }

    /**
     * 测试场景：管理端读取三套完整预置并从空状态导入内置演示。
     * 前置条件：预置服务按 01/02/03 返回完整内容，导入请求携带专用确认短语。
     * 期望结果：GET 保持稳定数组顺序，POST 把确认语交给 fixture 服务并返回状态。
     * 断言重点：两个接口仍位于同一 test-profile + enabled 控制器，不新增独立暴露面。
     */
    @Test
    void exposesSkillPresetsAndBuiltinImportEndpoints() throws Exception {
        org.mockito.Mockito.when(presets.presets())
                .thenReturn(java.util.List.of(
                        new SkillPresetService.SkillPreset(
                                "01-initial", "初始稳定版", "company-material-fact-check", "first", java.util.List.of()),
                        new SkillPresetService.SkillPreset(
                                "02-improved", "优化候选版", "company-material-fact-check", "second", java.util.List.of()),
                        new SkillPresetService.SkillPreset(
                                "03-regression", "回归失败版", "company-material-fact-check", "third", java.util.List.of())));
        org.mockito.Mockito.when(service.status()).thenReturn(new DemoStateView(Map.of(), Map.of()));

        mvc.perform(get("/api/admin/demo-state/skill-presets"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].id")
                        .value("01-initial"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[2].id")
                        .value("03-regression"));
        mvc.perform(post("/api/admin/demo-state/import-builtin")
                        .header("Idempotency-Key", "demo-import-builtin-001")
                        .header("X-Confirmation-Phrase", "导入内置演示数据"))
                .andExpect(status().isOk());

        verify(builtinFixture).importBuiltin("导入内置演示数据");
    }

    /**
     * 测试场景：两个导入 POST 缺少或携带非法 Idempotency-Key。
     * 前置条件：确认短语和请求体均合法，排除其他 HTTP 合同错误。
     * 期望结果：请求在调用快照或 fixture 服务前返回 4xx。
     * 断言重点：两个导入端点必须同时复用 RequestId 的 8–80 位安全字符校验。
     */
    @Test
    void requiresValidIdempotencyKeyForBothImportPosts() throws Exception {
        mvc.perform(post("/api/admin/demo-state/import")
                        .header("X-Confirmation-Phrase", "导入快照")
                        .contentType("application/zip")
                        .content("zip"))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/api/admin/demo-state/import-builtin")
                        .header("Idempotency-Key", "bad key")
                        .header("X-Confirmation-Phrase", "导入内置演示数据"))
                .andExpect(status().is4xxClientError());

        org.mockito.Mockito.verifyNoInteractions(snapshots, builtinFixture);
    }

    /**
     * 测试场景：自定义导入成功后以同键重试，并再次携带错误确认短语探测缓存绕过。
     * 前置条件：服务状态为固定脱敏对象，原始 ZIP 字节不访问真实数据。
     * 期望结果：合法重试返回首次结果且只导入一次；错误确认语即使命中历史键也返回 4xx。
     * 断言重点：确认语必须在读取幂等 Future 前逐次校验。
     */
    @Test
    void retriesSnapshotImportOnceButNeverCachesConfirmationAuthorization() throws Exception {
        org.mockito.Mockito.when(service.status()).thenReturn(new DemoStateView(Map.of(), Map.of()));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/admin/demo-state/import")
                            .header("Idempotency-Key", "snapshot-retry-001")
                            .header("X-Confirmation-Phrase", "导入快照")
                            .contentType("application/zip")
                            .content("zip"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/admin/demo-state/import")
                        .header("Idempotency-Key", "snapshot-retry-001")
                        .header("X-Confirmation-Phrase", "错误确认语")
                        .contentType("application/zip")
                        .content("zip"))
                .andExpect(status().is4xxClientError());

        verify(snapshots, times(1)).importFrom(org.mockito.ArgumentMatchers.any(), eq("导入快照"));
    }

    /**
     * 测试场景：内置导入成功后以同键重试，并再次携带错误确认短语探测缓存绕过。
     * 前置条件：fixture 与状态服务均为不触碰真实数据的 Mock。
     * 期望结果：合法重试只调用一次 fixture；错误确认语即使命中同一历史键也返回 4xx。
     * 断言重点：两个导入 POST 都把确认语作为逐次请求授权，而不是幂等缓存身份的一部分。
     */
    @Test
    void retriesBuiltinImportOnceButNeverCachesConfirmationAuthorization() throws Exception {
        org.mockito.Mockito.when(service.status()).thenReturn(new DemoStateView(Map.of(), Map.of()));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/admin/demo-state/import-builtin")
                            .header("Idempotency-Key", "builtin-retry-001")
                            .header("X-Confirmation-Phrase", "导入内置演示数据"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/admin/demo-state/import-builtin")
                        .header("Idempotency-Key", "builtin-retry-001")
                        .header("X-Confirmation-Phrase", "错误确认语"))
                .andExpect(status().is4xxClientError());

        verify(builtinFixture, times(1)).importBuiltin("导入内置演示数据");
    }

    /**
     * 测试场景：非 test profile 即使错误地开启配置，也尝试加载演示管理控制器。
     * 前置条件：上下文只注册控制器及其服务 Mock，不创建业务基础设施。
     * 期望结果：Profile 条件阻止控制器 Bean 装配。
     * 断言重点：生产或开发环境不能仅凭一个配置开关暴露清空接口。
     */
    @Test
    void doesNotAssembleControllerOutsideTestProfile() {
        contextRunner("prod", true).run(context -> org.assertj.core.api.Assertions.assertThat(context)
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
        contextRunner("test", false).run(context -> org.assertj.core.api.Assertions.assertThat(context)
                .doesNotHaveBean(DemoStateController.class));
    }

    /**
     * 测试场景：test profile 明确开启 demo-admin.enabled。
     * 前置条件：最小上下文提供控制器依赖且不加载完整 Web、数据库或文件存储基础设施。
     * 期望结果：控制器确实作为 Spring Bean 装配，而非仅在 standalone MockMvc 中可用。
     * 断言重点：双重条件既不能在生产误装配，也不能因条件表达式错误而在测试环境遗漏端点。
     */
    @Test
    void assemblesControllerWhenTestProfileAndFeatureSwitchAreEnabled() {
        contextRunner("test", true).run(context -> org.assertj.core.api.Assertions.assertThat(context)
                .hasSingleBean(DemoStateController.class));
    }

    /** 为条件测试构造最小上下文，显式提供控制器的唯一构造器依赖。 */
    private static ApplicationContextRunner contextRunner(String profile, boolean enabled) {
        return new ApplicationContextRunner()
                .withUserConfiguration(DemoStateController.class)
                .withBean(DemoStateService.class, () -> mock(DemoStateService.class))
                .withBean(SnapshotArchiveService.class, () -> mock(SnapshotArchiveService.class))
                .withBean(SkillPresetService.class, () -> mock(SkillPresetService.class))
                .withBean(BuiltinDemoFixtureService.class, () -> mock(BuiltinDemoFixtureService.class))
                .withBean(DemoImportIdempotency.class, DemoImportIdempotency::new)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withPropertyValues("workbench.demo-admin.enabled=" + enabled);
    }
}
