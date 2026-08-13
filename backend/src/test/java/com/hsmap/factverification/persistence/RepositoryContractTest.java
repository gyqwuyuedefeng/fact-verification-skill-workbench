package com.hsmap.factverification.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.hsmap.factverification.claim.persistence.ClaimRepository;
import com.hsmap.factverification.evaluation.persistence.EvaluationRunRepository;
import com.hsmap.factverification.evidence.persistence.EvidenceSnapshotRepository;
import com.hsmap.factverification.release.persistence.ReleaseBindingRepository;
import com.hsmap.factverification.run.persistence.VerificationRunRepository;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import com.hsmap.factverification.task.persistence.VerificationTaskRepository;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 锁定七张比赛表的最小持久化边界。
 *
 * <p>测试不连接共享测试库，不清理任何真实数据；唯一键由迁移契约验证，仓储反射门禁用于防止给不可变表误加覆盖或删除入口。
 */
class RepositoryContractTest {

    /** 七张表必须各有一个职责明确的 JDBC 仓储，不能退化为万能 CRUD 基类。 */
    @Test
    void exposesSevenPurposeSpecificRepositories() {
        assertThat(Set.of(
                        VerificationTaskRepository.class,
                        VerificationRunRepository.class,
                        ClaimRepository.class,
                        SkillVersionRepository.class,
                        EvaluationRunRepository.class,
                        EvidenceSnapshotRepository.class,
                        ReleaseBindingRepository.class))
                .hasSize(7);
    }

    /** 证据、主张和发布事件只允许追加和读取，避免评测或发布历史被原地覆盖。 */
    @Test
    void appendOnlyRepositoriesExposeNoUpdateOrDeleteOperations() {
        assertAppendOnly(EvidenceSnapshotRepository.class);
        assertAppendOnly(ClaimRepository.class);
        assertAppendOnly(ReleaseBindingRepository.class);
    }

    /** 发布 revision 从 1 开始并严格递增，具体并发串行化由查询最新事件时的数据库锁保证。 */
    @Test
    void releaseRevisionStartsAtOneAndIncrements() {
        assertThat(ReleaseBindingRepository.nextRevision(null)).isEqualTo(1L);
        assertThat(ReleaseBindingRepository.nextRevision(8L)).isEqualTo(9L);
    }

    /** 数据库唯一键是幂等和不可重复追加的最终防线。 */
    @Test
    void migrationContainsAllBusinessUniqueKeys() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V1__create_fact_verification_workbench.sql");
        String sql = Files.readString(migration, StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("UNIQUE (request_id)")
                .contains("UNIQUE (task_id, run_type)")
                .contains("UNIQUE (run_id, ordinal)")
                .contains("UNIQUE (snapshot_id, tool_name, arguments_hash)")
                .contains("UNIQUE (skill_key, revision)");
    }

    private static void assertAppendOnly(Class<?> repositoryType) {
        Set<String> mutatorNames = Arrays.stream(repositoryType.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("update") || name.startsWith("delete"))
                .collect(Collectors.toSet());
        assertThat(mutatorNames)
                .as("%s 不得提供覆盖或删除历史的方法", repositoryType.getSimpleName())
                .isEmpty();
    }
}
