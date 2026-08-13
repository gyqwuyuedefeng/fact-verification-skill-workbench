package com.hsmap.factverification.mcp.config;

import com.hsmap.factverification.mcp.shared.ServiceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动时证明 MCP 的唯一 PostgreSQL 会话既只读，又位于批准的比赛 schema。 */
@Component
public final class SnapshotDatabaseBoundaryVerifier implements ApplicationRunner {

    private final DataSource dataSource;
    private final EnterpriseEvidenceProperties.Snapshot expected;

    /** 注入 Boot 创建且声明 read-only 的快照连接池。 */
    public SnapshotDatabaseBoundaryVerifier(DataSource dataSource, EnterpriseEvidenceProperties properties) {
        this.dataSource = dataSource;
        this.expected = properties.snapshot();
    }

    /** 在 MCP 开始接收请求前查询 PostgreSQL 当前 database/schema 与会话只读状态。 */
    @Override
    public void run(ApplicationArguments args) throws SQLException {
        if (!expected.verifyOnStartup()) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select current_database(), current_schema(), current_setting('transaction_read_only')");
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()
                    || !expected.expectedDatabase().equals(resultSet.getString(1))
                    || !expected.expectedSchema().equals(resultSet.getString(2))
                    || !"on".equalsIgnoreCase(resultSet.getString(3))) {
                throw new ServiceException("MCP_SNAPSHOT_BOUNDARY_MISMATCH", "快照数据源不是批准的只读测试库边界");
            }
        }
    }
}
