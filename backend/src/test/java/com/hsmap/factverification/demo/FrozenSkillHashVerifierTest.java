package com.hsmap.factverification.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.hsmap.factverification.agent.FrozenSkillLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 被测试对象：测试专用的 {@link FrozenSkillHashVerifier}。
 * 测试目的：让 Task 8 验收直接复用生产 {@link FrozenSkillLoader#contentHash(Path)}，避免用另一套排序或哈希实现产生假失败。
 * 覆盖范围：snapshot/runtime 双目录、数据库 hash、版本十二位后缀、错误 hash，以及通过只读查询文件核验真实 storageRoot。
 * 前置条件：真实状态核验仅在显式提供 task8.storage-root 与 task8.frozen-skill-expectations 时执行；普通测试只访问临时目录。
 */
class FrozenSkillHashVerifierTest {

    @TempDir
    Path temporaryDirectory;

    /**
     * 测试场景：同一冻结版本的 snapshot 与 runtime 内容均与数据库期望一致。
     * 前置条件：临时目录包含生产布局要求的唯一 Skill，期望文件记录 canonical UUID、version 和生产 hash。
     * 期望结果：验证器返回一条成功结果，且两个实算 hash 与 version 后缀都匹配。
     * 断言重点：实算值必须来自 FrozenSkillLoader，而不是测试内复制的 Node/Java 哈希算法。
     */
    @Test
    void verifiesSnapshotAndRuntimeWithProductionContentHash() throws Exception {
        UUID versionId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        Path snapshot = frozenRoot("skill-snapshots", versionId);
        Path runtime = frozenRoot("skill-runtime", versionId);
        Files.createDirectories(snapshot.resolve("company-material-fact-check/references"));
        Files.writeString(snapshot.resolve("company-material-fact-check/SKILL.md"), "# 测试 Skill\n");
        Files.writeString(
                snapshot.resolve("company-material-fact-check/references/rule.md"), "固定参考内容\n");
        copyTree(snapshot, runtime);
        String hash = new FrozenSkillLoader().contentHash(snapshot);
        Path expectations = temporaryDirectory.resolve("expected.tsv");
        Files.writeString(expectations, versionId + "\tv0.1.0+" + hash.substring(0, 12) + "\t" + hash + "\n");

        List<FrozenSkillHashVerifier.VerifiedVersion> verified =
                FrozenSkillHashVerifier.verify(temporaryDirectory, expectations);

        assertThat(verified).singleElement().satisfies(result -> {
            assertThat(result.versionId()).isEqualTo(versionId);
            assertThat(result.snapshotHash()).isEqualTo(hash);
            assertThat(result.runtimeHash()).isEqualTo(hash);
        });
    }

    /**
     * 测试场景：期望文件的数据库 hash 与冻结目录实际内容不一致。
     * 前置条件：snapshot/runtime 内容彼此相同，但 TSV 中记录 64 个零。
     * 期望结果：验证器失败关闭并指出数据库 hash 不匹配。
     * 断言重点：不能因为两份目录相同就跳过与数据库权威值的比较。
     */
    @Test
    void rejectsHashThatOnlyMatchesNeitherDatabaseExpectation() throws Exception {
        UUID versionId = UUID.fromString("10000000-0000-0000-0000-000000000002");
        Path snapshot = frozenRoot("skill-snapshots", versionId);
        Path runtime = frozenRoot("skill-runtime", versionId);
        Files.createDirectories(snapshot.resolve("company-material-fact-check"));
        Files.writeString(snapshot.resolve("company-material-fact-check/SKILL.md"), "# 被篡改的 Skill\n");
        copyTree(snapshot, runtime);
        Path expectations = temporaryDirectory.resolve("invalid.tsv");
        Files.writeString(expectations, versionId + "\tv0.1.0+000000000000\t" + "0".repeat(64) + "\n");

        assertThatThrownBy(() -> FrozenSkillHashVerifier.verify(temporaryDirectory, expectations))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据库 content_hash");
    }

    /**
     * 测试场景：操作者显式提供只读查询导出的真实冻结版本清单。
     * 前置条件：两个系统属性必须同时存在；缺省时该集成核验透明跳过，不读取开发机共享数据。
     * 期望结果：每个清单版本的双目录、数据库 hash 与十二位 version 后缀全部一致，并输出可留档的逐版本证据。
     * 断言重点：该入口用于真实验收时不得以测试 fixture 代替当前 storageRoot。
     */
    @Test
    void verifiesConfiguredRealStorageWhenExplicitlyRequested() throws Exception {
        String storageRoot = System.getProperty("task8.storage-root");
        String expectations = System.getProperty("task8.frozen-skill-expectations");
        assumeTrue(storageRoot != null && expectations != null, "未请求 Task 8 真实冻结目录核验");

        List<FrozenSkillHashVerifier.VerifiedVersion> verified =
                FrozenSkillHashVerifier.verify(Path.of(storageRoot), Path.of(expectations));

        assertThat(verified).isNotEmpty();
        verified.forEach(result -> System.out.printf(
                "TASK8_FROZEN_HASH_OK id=%s version=%s hash=%s%n",
                result.versionId(), result.version(), result.snapshotHash()));
    }

    private Path frozenRoot(String managedDirectory, UUID versionId) {
        return temporaryDirectory.resolve(managedDirectory).resolve(versionId.toString());
    }

    private static void copyTree(Path source, Path target) throws Exception {
        try (var walk = Files.walk(source)) {
            for (Path current : walk.toList()) {
                Path destination = target.resolve(source.relativize(current));
                if (Files.isDirectory(current)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(current, destination);
                }
            }
        }
    }
}
