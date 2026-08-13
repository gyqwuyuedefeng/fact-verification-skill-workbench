package com.hsmap.factverification.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 被测试对象：带兼容构造器的 {@link DemoAdminProperties} 配置绑定。
 * 测试目的：复现真实 test profile 启动时配置 Bean 无法选择规范构造器的问题，防止单元测试因直接 new 对象而遗漏装配失败。
 * 覆盖范围：完整六字段配置绑定及 Spring 上下文中的单 Bean 创建。
 * 前置条件：使用隔离的 ApplicationContextRunner，不连接数据库、不读取附件，也不触发任何演示状态写操作。
 */
class DemoAdminPropertiesBindingTest {

    /**
     * 测试场景：配置类同时保留规范构造器和旧测试兼容构造器时，由 Spring Boot 完成属性绑定。
     * 前置条件：提供真实 application.yml 对应的全部六个字段，避免默认值掩盖构造器选择问题。
     * 期望结果：上下文启动成功，且路径与归档限制逐项绑定到规范构造器参数。
     * 断言重点：Spring 必须创建唯一配置 Bean，不能退化为查找无参构造器。
     */
    @Test
    void bindsCanonicalRecordConstructorWhenCompatibilityConstructorAlsoExists() {
        new ApplicationContextRunner()
                .withUserConfiguration(BindingConfiguration.class)
                .withPropertyValues(
                        "workbench.demo-admin.enabled=true",
                        "workbench.demo-admin.demo-material-root=evals/demo-materials",
                        "workbench.demo-admin.skill-preset-root=skills/presets",
                        "workbench.demo-admin.max-archive-bytes=209715200",
                        "workbench.demo-admin.max-entry-count=2000",
                        "workbench.demo-admin.max-expanded-bytes=524288000")
                .run(context -> {
                    assertThat(context).hasSingleBean(DemoAdminProperties.class);
                    assertThat(context.getBean(DemoAdminProperties.class))
                            .isEqualTo(new DemoAdminProperties(
                                    true,
                                    Path.of("evals/demo-materials"),
                                    Path.of("skills/presets"),
                                    209_715_200L,
                                    2_000,
                                    524_288_000L));
                });
    }

    /** 只启用目标配置 Bean；隔离上下文不会扫描控制器、数据库或文件系统组件。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DemoAdminProperties.class)
    static class BindingConfiguration {}
}
