package com.hsmap.factverification.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.hsmap.factverification.evaluation.persistence.EvaluationRunRepository;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 被测试对象：FactVerificationApplication 在 test profile 下包含快照组件的完整 Web 启动。
 * 测试目的：验证真实 Spring Boot/Tomcat 上下文能够装配并在测试结束时自动关闭，同时不连接或写入共享测试数据库。
 * 覆盖范围：随机端口 Web 容器、test profile、Nacos 注册关闭和启动 Runner 的数据库依赖替身。
 * 前置条件：Flyway 与数据库身份查询显式关闭，DataSource 和重启恢复仓储由 Spring 测试替身提供。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.main.lazy-initialization=true",
            "spring.flyway.enabled=false",
            "workbench.database.verify-on-startup=false"
        })
@ActiveProfiles("test")
class SnapshotStartupTest {

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Autowired
    private DemoAdminProperties demoAdminProperties;

    /** 阻止验收启动连接共享 PostgreSQL；身份检查已由测试配置明确关闭。 */
    @MockitoBean
    private DataSource dataSource;

    /** 阻止启动恢复动作写共享评测表，当前测试只验证应用装配和容器生命周期。 */
    @MockitoBean
    private EvaluationRunRepository evaluationRuns;

    /**
     * 测试场景：以 test profile 启动包含快照端点的完整 Web 应用。
     * 前置条件：容器使用随机端口，数据库相关副作用由 Mock 隔离。
     * 期望结果：Tomcat 成功监听端口、test profile 生效且 Nacos 注册保持关闭。
     * 断言重点：完成 Task 5 后应用仍可启动，并遵守项目测试环境的注册边界。
     */
    @Test
    void startsAndStopsApplicationWithTestProfileAndRegistrationDisabled() {
        assertThat(port).isPositive();
        assertThat(environment.getActiveProfiles()).containsExactly("test");
        assertThat(environment.getProperty("spring.cloud.nacos.discovery.register-enabled", Boolean.class))
                .isFalse();
        assertThat(demoAdminProperties.storageSwapMode())
                .isEqualTo(DemoAdminProperties.StorageSwapMode.COPY_VERIFY);
    }
}
