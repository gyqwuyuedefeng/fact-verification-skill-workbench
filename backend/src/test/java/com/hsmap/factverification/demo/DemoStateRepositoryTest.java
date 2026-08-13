package com.hsmap.factverification.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 被测试对象：DemoStateRepository 的固定遗留核验恢复 SQL。
 * 测试目的：锁定只处理“任务运行中、主运行尚未启动且已超过一小时”的最小恢复边界。
 * 覆盖范围：固定 SQL 条件、无调用方参数、任务与运行更新数量投影。
 * 前置条件：JdbcTemplate 使用 Mock；测试不连接数据库，也不产生任何业务写入。
 */
class DemoStateRepositoryTest {

    /**
     * 测试场景：仓储执行遗留主运行恢复。
     * 前置条件：数据库投影返回一条任务和一条运行已恢复。
     * 期望结果：返回数量不变，并且 SQL 同时包含全部 stale 条件及脱敏失败结果。
     * 断言重点：SQL 不接受任务、运行或时间阈值参数，避免端点演变为通用任务修改入口。
     */
    @Test
    void recoversOnlyFixedStalePendingPrimaryRunsWithoutCallerParameters() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
                        anyString(), any(RowMapper.class)))
                .thenReturn(new DemoStateRepository.StaleRecoveryCounts(1, 1));
        DemoStateRepository repository = new DemoStateRepository(jdbcTemplate);

        DemoStateRepository.StaleRecoveryCounts result = repository.recoverStalePendingPrimaryRuns();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), any(RowMapper.class));
        String normalized = sql.getValue().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        assertThat(result).isEqualTo(new DemoStateRepository.StaleRecoveryCounts(1, 1));
        assertThat(normalized)
                .contains("t.status = 'running'")
                .contains("r.run_type = 'primary'")
                .contains("r.status = 'pending'")
                .contains("r.started_at is null")
                .contains("r.created_at < current_timestamp - interval '1 hour'")
                .doesNotContain("r.created_at <= current_timestamp - interval '1 hour'")
                .contains("other.status in ('pending', 'running')")
                .contains("stale_run_recovered")
                .contains("finished_at = current_timestamp")
                .contains("updated_at = current_timestamp");
        assertThat(sql.getValue()).doesNotContain("?");
    }
}
