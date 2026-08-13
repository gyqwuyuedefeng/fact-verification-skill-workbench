package com.hsmap.factverification.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.hsmap.factverification.release.InitialStableBootstrapService;
import com.hsmap.factverification.release.ReleaseService;
import com.hsmap.factverification.skill.SkillVersionService;
import com.hsmap.factverification.task.VerificationTaskService;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 被测试对象：使用 {@link Transactional} 的应用服务。
 * 测试目的：确保无接口的事务服务可以被 Spring Framework 7 创建 CGLIB 代理。
 * 覆盖范围：任务、初始 Stable、发布和 Skill 版本四个事务入口。
 * 前置条件：这些服务采用类代理，不引入仅为代理而存在的空接口。
 */
class TransactionalProxyCompatibilityTest {

    /**
     * 测试场景：Spring 为所有事务应用服务创建类代理。
     * 前置条件：服务类或其方法声明 Transactional，且没有统一业务接口可用于 JDK 代理。
     * 期望结果：所有目标类均允许继承。
     * 断言重点：任何 final 事务服务都会在可执行 JAR 启动时失败，必须被该测试提前拦截。
     */
    @Test
    void transactionalApplicationServicesAllowClassProxying() {
        List<Class<?>> transactionalServices = List.of(
                VerificationTaskService.class,
                InitialStableBootstrapService.class,
                ReleaseService.class,
                SkillVersionService.class);

        assertThat(transactionalServices)
                .allSatisfy(serviceType -> assertThat(Modifier.isFinal(serviceType.getModifiers()))
                        .as("%s 必须允许 Spring 事务类代理", serviceType.getName())
                        .isFalse());
    }
}
