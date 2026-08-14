package com.hsmap.factverification.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hsmap.factverification.run.persistence.VerificationRunRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 被测试对象：{@link VerificationRunRepository#listShadowRuns()} 的 Stable/Candidate 主张对比查询。
 * 测试目的：锁定两侧主张必须先按当前 PRIMARY 与 SHADOW 运行过滤，再按 ordinal 做全连接的业务边界。
 * 覆盖范围：查询中 PRIMARY、SHADOW 两个派生表的运行隔离条件；不连接共享测试库，也不写入任何业务数据。
 * 前置条件：JdbcTemplate 使用 Mock，仅捕获仓储提交的 PostgreSQL 查询文本。
 */
class ShadowComparisonQueryContractTest {

    /**
     * 测试场景：同一任务下存在一条 Stable 主张和一条内容相同的 Candidate 主张。
     * 前置条件：影子汇总查询通过 JdbcTemplate 执行，但数据库结果由空列表替代。
     * 期望结果：FULL JOIN 两侧都先限定为当前运行，其他运行的主张不能作为未匹配行重复计入差异。
     * 断言重点：查询必须包含分别按 s.id 与 p.id 过滤的两个派生表，不能在全连接后才过滤单侧。
     */
    @Test
    void filtersBothClaimSidesBeforeComparingOrdinals() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(any(String.class), org.mockito.ArgumentMatchers.<RowMapper<Object>>any()))
                .thenReturn(List.of());

        new VerificationRunRepository(jdbcTemplate).listShadowRuns();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any());
        String normalized = sql.getValue().replaceAll("\\s+", " ");
        assertThat(normalized)
                .contains("from (select * from test.claim where run_id = s.id) sc")
                .contains("full join (select * from test.claim where run_id = p.id) pc")
                .doesNotContain("where sc.run_id = s.id or sc.run_id is null");
    }
}
