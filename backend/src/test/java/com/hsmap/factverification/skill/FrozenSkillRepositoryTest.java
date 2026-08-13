package com.hsmap.factverification.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hsmap.factverification.agent.FrozenSkillLoader;
import com.hsmap.factverification.shared.ServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 锁定 snapshot/runtime 双目录层级、只读装载和篡改拒绝。 */
class FrozenSkillRepositoryTest {

    @TempDir
    Path tempDir;

    /** runtimeRoot 必须是 Skill 子目录的父目录，并能按冻结 hash 装载。 */
    @Test
    void createsAgentScopeParentDirectoryAndRejectsTampering() throws Exception {
        FrozenSkillStorage storage =
                new FrozenSkillStorage(tempDir.resolve("skill-snapshots"), tempDir.resolve("skill-runtime"));
        UUID versionId = UUID.randomUUID();

        FrozenSkillSnapshot snapshot = storage.freeze(
                versionId,
                "---\nname: company-material-fact-check\ndescription: test\n---\n# Test\n",
                List.of(new SkillReference("references/rules.md", "# Rules\n")));

        assertThat(snapshot.runtimeRoot().resolve("company-material-fact-check/SKILL.md"))
                .isRegularFile();
        assertThat(new FrozenSkillLoader()
                        .load(snapshot.runtimeRoot(), snapshot.contentHash())
                        .getAllSkillNames())
                .containsExactly("company-material-fact-check");

        Path skillFile = snapshot.runtimeRoot().resolve("company-material-fact-check/SKILL.md");
        assertThat(skillFile.toFile().canWrite()).isFalse();
        skillFile.toFile().setWritable(true, false);
        Files.writeString(skillFile, "tampered");
        assertThatThrownBy(() -> new FrozenSkillLoader().load(snapshot.runtimeRoot(), snapshot.contentHash()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("FROZEN_SKILL_HASH_MISMATCH");
    }
}
