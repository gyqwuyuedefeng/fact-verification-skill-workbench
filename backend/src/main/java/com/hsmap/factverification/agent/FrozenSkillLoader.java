package com.hsmap.factverification.agent;

import com.hsmap.factverification.compat.AgentScopeRuntimeCompatibility;
import com.hsmap.factverification.shared.ServiceException;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** 校验冻结目录的完整内容 hash 后，以版本父目录创建只读 AgentScope Skill repository。 */
public final class FrozenSkillLoader {

    private static final String SKILL_KEY = "company-material-fact-check";

    /** 只有目录内容仍等于数据库记录的 content hash 时才允许进入 Agent。 */
    public FileSystemSkillRepository load(Path versionRuntimeRoot, String expectedHash) {
        if (expectedHash == null || !expectedHash.equals(contentHash(versionRuntimeRoot))) {
            throw new ServiceException("FROZEN_SKILL_HASH_MISMATCH", "冻结 Skill 内容已变化，拒绝装载");
        }
        Path skillFile = versionRuntimeRoot.resolve(SKILL_KEY).resolve("SKILL.md");
        if (!Files.isRegularFile(skillFile)) {
            throw new ServiceException("FROZEN_SKILL_LAYOUT_INVALID", "冻结 Skill 目录层级无效");
        }
        return AgentScopeRuntimeCompatibility.readOnlySkillRepository(versionRuntimeRoot);
    }

    /**
     * 从通过哈希门禁的 AgentScope 只读仓库读取唯一 Skill 正文。
     *
     * <p>当前 MVP 每个版本只有一个 company-material-fact-check Skill。与其让模型在每次核验前自行决定
     * 是否调用动态加载工具，这里在 Agent 创建时就确定性地读入已冻结内容。版本目录、AgentScope 解析、只读属性和整目录 hash
     * 仍是必经门禁，因此不会绕过 Skill 版本资产。
     */
    public String loadContent(Path versionRuntimeRoot, String expectedHash) {
        try (FileSystemSkillRepository repository = load(versionRuntimeRoot, expectedHash)) {
            List<AgentSkill> skills = repository.getAllSkills();
            if (skills.size() != 1 || !SKILL_KEY.equals(skills.get(0).getName())) {
                throw new ServiceException("FROZEN_SKILL_LAYOUT_INVALID", "冻结版本必须仅包含指定 Skill");
            }
            String content = skills.get(0).getSkillContent();
            if (content == null || content.isBlank()) {
                throw new ServiceException("FROZEN_SKILL_LAYOUT_INVALID", "冻结 Skill 正文为空");
            }
            String combinedContent = appendReferences(
                    content, versionRuntimeRoot.resolve(SKILL_KEY).resolve("references"));
            // 读取 references 后再次核验整目录 hash，关闭“第一次校验后文件被替换”的时间窗口。
            // 冻结目录通常物理只读，这一检查仍能让异常挂载或误操作以失败关闭，而不是把未登记内容送入模型。
            if (!expectedHash.equals(contentHash(versionRuntimeRoot))) {
                throw new ServiceException("FROZEN_SKILL_HASH_MISMATCH", "冻结 Skill 内容已变化，拒绝装载");
            }
            return combinedContent;
        } catch (IOException exception) {
            throw new ServiceException("FROZEN_SKILL_UNREADABLE", "无法读取冻结 Skill 参考文件");
        }
    }

    /**
     * 将已冻结 references 按规范化相对路径排序后追加到系统提示词。
     *
     * <p>AgentScope 的 Skill repository 只返回 SKILL.md 正文，而当前单 Skill MVP 又刻意关闭动态加载工具。若不在此处注入，
     * reference 文件虽然参与版本 hash，却永远不会影响运行结果。固定排序和明确边界标记使同一版本在每次评测中得到完全相同的提示词。
     *
     * @param skillContent AgentScope 解析后的 SKILL.md 正文
     * @param referencesRoot 冻结版本的 references 目录；目录不存在时视为空集合
     * @return 正文与全部 reference 内容组成的确定性字符串
     * @throws IOException 任一 reference 无法读取时失败关闭
     */
    private static String appendReferences(String skillContent, Path referencesRoot) throws IOException {
        if (!Files.isDirectory(referencesRoot)) {
            return skillContent;
        }
        List<Path> references;
        try (var walk = Files.walk(referencesRoot)) {
            references = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path ->
                            referencesRoot.relativize(path).toString().replace('\\', '/')))
                    .toList();
        }
        StringBuilder combined = new StringBuilder(skillContent);
        Path skillRoot = referencesRoot.getParent();
        for (Path reference : references) {
            String relativePath = skillRoot.relativize(reference).toString().replace('\\', '/');
            combined.append("\n\n--- Skill Reference: ")
                    .append(relativePath)
                    .append(" ---\n")
                    .append(Files.readString(reference, StandardCharsets.UTF_8));
        }
        return combined.toString();
    }

    /**
     * 按相对路径排序并同时 hash 路径与文件内容，保证 references 增删改都能被识别。
     */
    public String contentHash(Path versionRuntimeRoot) {
        Path skillRoot = versionRuntimeRoot.resolve(SKILL_KEY).normalize();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> files;
            try (var walk = Files.walk(skillRoot)) {
                files = walk.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(
                                path -> skillRoot.relativize(path).toString()))
                        .toList();
            }
            if (files.isEmpty()) {
                throw new ServiceException("FROZEN_SKILL_LAYOUT_INVALID", "冻结 Skill 没有内容文件");
            }
            byte[] buffer = new byte[8192];
            for (Path file : files) {
                digest.update(
                        skillRoot.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try (InputStream input = Files.newInputStream(file)) {
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        digest.update(buffer, 0, count);
                    }
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new ServiceException("FROZEN_SKILL_UNREADABLE", "无法读取冻结 Skill");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256", exception);
        }
    }
}
