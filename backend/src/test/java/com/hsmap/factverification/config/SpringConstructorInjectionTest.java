package com.hsmap.factverification.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.hsmap.factverification.skill.SkillVersionService;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 被测试对象：包含生产构造器和包内测试构造器的 Spring 组件。
 * 测试目的：锁定 Spring Framework 7 对多构造器组件需要显式注入入口的启动约束。
 * 覆盖范围：数据库边界校验器与 Skill 版本服务两个多构造器组件。
 * 前置条件：仅检查构造器元数据，不连接数据库、不读取 Skill 文件。
 */
class SpringConstructorInjectionTest {

    /**
     * 测试场景：数据库边界组件保留测试构造器时仍有唯一生产注入入口。
     * 前置条件：组件声明了两个构造器。
     * 期望结果：恰好一个构造器具有 Autowired 标记。
     * 断言重点：Spring 不会回退查找不存在的无参构造器。
     */
    @Test
    void databaseBoundaryHasOneExplicitInjectionConstructor() {
        assertSingleInjectionConstructor(DatabaseBoundaryVerifier.class);
    }

    /**
     * 测试场景：Skill 服务允许测试注入隔离存储目录时仍能由 Spring 启动。
     * 前置条件：服务同时包含生产四依赖构造器和包内五依赖测试构造器。
     * 期望结果：只有生产构造器被标记为注入入口。
     * 断言重点：测试便利构造器不得改变运行时装配选择。
     */
    @Test
    void skillVersionServiceHasOneExplicitInjectionConstructor() {
        assertSingleInjectionConstructor(SkillVersionService.class);
    }

    /** 统计运行时可见的注入标记，统一约束所有带测试构造器的 Spring 组件。 */
    private static void assertSingleInjectionConstructor(Class<?> componentType) {
        long marked = Arrays.stream(componentType.getDeclaredConstructors())
                .filter(SpringConstructorInjectionTest::isAutowired)
                .count();
        assertThat(componentType.getDeclaredConstructors()).hasSizeGreaterThan(1);
        assertThat(marked).isEqualTo(1);
    }

    /** Spring 7 通过该运行时注解选择多构造器组件的唯一装配入口。 */
    private static boolean isAutowired(Constructor<?> constructor) {
        return constructor.isAnnotationPresent(Autowired.class);
    }
}
