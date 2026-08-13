package com.hsmap.factverification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hsmap.factverification.shared.ServiceException;
import java.net.URI;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 被测试对象：{@link DatabaseBoundaryVerifier}。
 * 测试目的：确保比赛后端只能连接批准的 database/schema，且组件能被 Spring 的构造器注入正常创建。
 * 覆盖范围：正确边界、错误边界以及多构造器组件的 Spring 装配。
 * 前置条件：身份比较测试不建立数据库连接；装配测试使用假的 DataSource 且不执行 Runner。
 */
class DatabaseBoundaryTest {

    private final DatabaseBoundaryVerifier verifier = new DatabaseBoundaryVerifier(
            null, new WorkbenchProperties.DatabaseBoundary("kjjr_inx_brain", "test", true));

    /**
     * 测试场景：数据库身份精确命中批准的比赛边界。
     * 前置条件：database 为 kjjr_inx_brain、schema 为 test。
     * 期望结果：身份门禁不抛出异常。
     * 断言重点：合法边界不会被误拦截。
     */
    @Test
    void acceptsApprovedCompetitionBoundary() {
        assertThatCode(() -> verifier.verifyIdentity(new DatabaseIdentity("kjjr_inx_brain", "test")))
                .doesNotThrowAnyException();
    }

    /**
     * 测试场景：database 或 schema 任一偏离批准边界。
     * 前置条件：分别输入 public schema 和其他 database。
     * 期望结果：两种偏离都抛出脱敏的业务异常。
     * 断言重点：异常码固定为 DATABASE_BOUNDARY_MISMATCH。
     */
    @Test
    void rejectsDatabaseOrSchemaMismatch() {
        assertThatThrownBy(() -> verifier.verifyIdentity(new DatabaseIdentity("kjjr_inx_brain", "public")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("DATABASE_BOUNDARY_MISMATCH");
        assertThatThrownBy(() -> verifier.verifyIdentity(new DatabaseIdentity("other", "test")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("DATABASE_BOUNDARY_MISMATCH");
    }

    /**
     * 测试场景：Spring 创建同时包含生产构造器和测试构造器的边界组件。
     * 前置条件：上下文只提供生产构造器所需的 DataSource 与 WorkbenchProperties。
     * 期望结果：Spring 选择生产构造器并成功创建唯一组件。
     * 断言重点：不能回退查找不存在的无参构造器，避免可执行 JAR 在启动阶段失败。
     */
    @Test
    void springSelectsProductionConstructor() {
        WorkbenchProperties properties = new WorkbenchProperties(
                new WorkbenchProperties.DatabaseBoundary("kjjr_inx_brain", "test", false),
                Path.of("data"),
                Path.of("evals/manifest.json"),
                Path.of("skills/company-material-fact-check"),
                new WorkbenchProperties.Model("", "/v1/chat/completions", "", ""),
                URI.create("http://127.0.0.1:19091/mcp"));

        new ApplicationContextRunner()
                .withBean(DataSource.class, () -> Mockito.mock(DataSource.class))
                .withBean(WorkbenchProperties.class, () -> properties)
                .withUserConfiguration(DatabaseBoundaryVerifier.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DatabaseBoundaryVerifier.class);
                });
    }
}
