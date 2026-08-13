package com.hsmap.factverification.skill;

import com.hsmap.factverification.agent.FrozenSkillLoader;
import com.hsmap.factverification.shared.ServiceException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 将 DRAFT 原子冻结到审核快照与 AgentScope 运行目录。
 *
 * <p>每个版本拥有独立父目录 `versionRoot/skillKey/SKILL.md`，符合 FileSystemSkillRepository 的扫描约定。
 */
public final class FrozenSkillStorage {

    public static final String SKILL_KEY = "company-material-fact-check";

    private final Path snapshotsRoot;
    private final Path runtimeRoot;
    private final FrozenSkillLoader loader = new FrozenSkillLoader();

    public FrozenSkillStorage(Path snapshotsRoot, Path runtimeRoot) {
        this.snapshotsRoot = snapshotsRoot.toAbsolutePath().normalize();
        this.runtimeRoot = runtimeRoot.toAbsolutePath().normalize();
    }

    /** 写入两个不可复用的版本目录并校验其内容 hash 完全一致。 */
    public FrozenSkillSnapshot freeze(UUID versionId, String skillMarkdown, List<SkillReference> references) {
        validateMarkdown(skillMarkdown);
        Path snapshotVersion = snapshotsRoot.resolve(versionId.toString());
        Path runtimeVersion = runtimeRoot.resolve(versionId.toString());
        if (Files.exists(snapshotVersion) || Files.exists(runtimeVersion)) {
            throw new ServiceException("FROZEN_SKILL_ALREADY_EXISTS", "该 Skill 版本目录已存在");
        }
        try {
            Files.createDirectories(snapshotsRoot);
            Files.createDirectories(runtimeRoot);
            Path snapshotTemp = Files.createTempDirectory(snapshotsRoot, ".freeze-");
            writeSkill(snapshotTemp, skillMarkdown, references);
            moveAtomically(snapshotTemp, snapshotVersion);

            Path runtimeTemp = Files.createTempDirectory(runtimeRoot, ".runtime-");
            copyTree(snapshotVersion, runtimeTemp);
            moveAtomically(runtimeTemp, runtimeVersion);

            String snapshotHash = loader.contentHash(snapshotVersion);
            String runtimeHash = loader.contentHash(runtimeVersion);
            if (!snapshotHash.equals(runtimeHash)) {
                throw new ServiceException("FROZEN_SKILL_COPY_MISMATCH", "Skill 运行副本与审核快照不一致");
            }
            makeReadOnly(snapshotVersion);
            makeReadOnly(runtimeVersion);
            return new FrozenSkillSnapshot(snapshotHash, snapshotVersion, runtimeVersion);
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceException("FROZEN_SKILL_WRITE_FAILED", "Skill 冻结目录写入失败");
        }
    }

    private static void writeSkill(Path versionRoot, String markdown, List<SkillReference> references)
            throws IOException {
        Path skillRoot = versionRoot.resolve(SKILL_KEY);
        Files.createDirectories(skillRoot);
        Files.writeString(skillRoot.resolve("SKILL.md"), markdown, StandardCharsets.UTF_8);
        for (SkillReference reference : references == null ? List.<SkillReference>of() : references) {
            Path relative = Path.of(reference.path()).normalize();
            if (relative.isAbsolute()
                    || relative.getNameCount() < 2
                    || !"references".equals(relative.getName(0).toString())
                    || relative.startsWith("..")
                    || reference.content() == null) {
                throw new ServiceException("SKILL_REFERENCE_PATH_INVALID", "参考文件必须位于 references/ 下");
            }
            Path target = skillRoot.resolve(relative).normalize();
            if (!target.startsWith(skillRoot.resolve("references"))) {
                throw new ServiceException("SKILL_REFERENCE_PATH_INVALID", "参考文件路径越界");
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, reference.content(), StandardCharsets.UTF_8);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void makeReadOnly(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                if (Files.isRegularFile(path)) {
                    path.toFile().setWritable(false, false);
                }
            });
        }
    }

    private static void validateMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank() || !markdown.contains("name: company-material-fact-check")) {
            throw new ServiceException("SKILL_MARKDOWN_INVALID", "SKILL.md 缺少固定 Skill 名称");
        }
    }
}
