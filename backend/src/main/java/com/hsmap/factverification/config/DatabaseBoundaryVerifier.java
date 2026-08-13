package com.hsmap.factverification.config;

import com.hsmap.factverification.shared.ServiceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 启动完成前核对 PostgreSQL 会话实际身份，避免仅凭 URL 字符串误判隔离边界。 */
@Component
@Order(0)
public final class DatabaseBoundaryVerifier implements ApplicationRunner {

    private final DataSource dataSource;
    private final WorkbenchProperties.DatabaseBoundary expected;

    /** 注入唯一工作台写数据源及批准边界。 */
    @Autowired
    public DatabaseBoundaryVerifier(DataSource dataSource, WorkbenchProperties properties) {
        this(dataSource, properties.database());
    }

    /** 供无数据库副作用的边界单元测试直接注入预期值。 */
    DatabaseBoundaryVerifier(DataSource dataSource, WorkbenchProperties.DatabaseBoundary expected) {
        this.dataSource = dataSource;
        this.expected = expected;
    }

    /** test/审核启动时查询真实会话；单元测试可显式关闭，生产默认不能关闭。 */
    @Override
    public void run(ApplicationArguments args) {
        if (!expected.verifyOnStartup()) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("select current_database(), current_schema()");
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new ServiceException("DATABASE_IDENTITY_UNAVAILABLE", "数据库身份查询没有返回结果");
            }
            verifyIdentity(new DatabaseIdentity(resultSet.getString(1), resultSet.getString(2)));
        } catch (SQLException exception) {
            throw new ServiceException("DATABASE_IDENTITY_UNAVAILABLE", "无法核对比赛数据库身份");
        }
    }

    /** 精确比较 database/schema；异常描述只报告身份，不携带连接串或凭据。 */
    void verifyIdentity(DatabaseIdentity actual) {
        if (!expected.expectedName().equals(actual.databaseName())
                || !expected.expectedSchema().equals(actual.schemaName())) {
            throw new ServiceException(
                    "DATABASE_BOUNDARY_MISMATCH",
                    "预期 "
                            + expected.expectedName()
                            + "."
                            + expected.expectedSchema()
                            + "，实际 "
                            + actual.databaseName()
                            + "."
                            + actual.schemaName());
        }
    }
}
