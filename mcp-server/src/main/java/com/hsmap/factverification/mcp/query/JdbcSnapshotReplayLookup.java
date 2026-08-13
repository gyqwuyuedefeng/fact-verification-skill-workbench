package com.hsmap.factverification.mcp.query;

import com.hsmap.factverification.mcp.shared.ServiceException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 只对 `test.evidence_snapshot` 执行 SELECT 的快照实现。 */
@Repository
public class JdbcSnapshotReplayLookup implements SnapshotReplayLookup {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public JdbcSnapshotReplayLookup(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    /** 成功快照返回原响应；冻结错误转为稳定业务异常，不回显数据库内容。 */
    @Override
    public Optional<Map<String, Object>> find(UUID snapshotId, String toolName, String argumentsHash) {
        return jdbcTemplate
                .query(
                        """
                        select response_json::text, error_code
                          from test.evidence_snapshot
                         where snapshot_id = ? and tool_name = ? and arguments_hash = ?
                        """,
                        (rs, rowNum) -> {
                            String errorCode = rs.getString("error_code");
                            if (errorCode != null) {
                                throw new ServiceException(errorCode, "证据快照记录了稳定失败");
                            }
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> response =
                                        jsonMapper.readValue(rs.getString("response_json"), Map.class);
                                return response;
                            } catch (JacksonException exception) {
                                throw new ServiceException("EVIDENCE_SNAPSHOT_INVALID", "证据快照格式无效");
                            }
                        },
                        snapshotId,
                        toolName,
                        argumentsHash)
                .stream()
                .findFirst();
    }
}
