package com.hsmap.factverification.demo;

import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.skill.SkillReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 读取随代码版本交付的三阶段 Skill 预置资产。
 *
 * <p>服务只认识 01 初始、02 优化、03 回归三个固定目录，返回完整 Markdown 并按稳定相对路径复制原始字节。它不读取 data
 * 运行目录，也不提供任意目录浏览或通用模板管理能力。
 */
@Service
public class SkillPresetService {

    private static final String SKILL_NAME = "company-material-fact-check";
    private static final List<String> PRESET_IDS = List.of("01-initial", "02-improved", "03-regression");
    private static final List<String> REFERENCE_PATHS =
            List.of("references/claim-normalization.md", "references/evidence-rules.md");
    private static final Map<String, String> LABELS = Map.of(
            "01-initial", "初始稳定版",
            "02-improved", "优化候选版",
            "03-regression", "回归失败版");

    private final Path presetRoot;

    /** 从演示管理配置取得受版本控制的 preset 根；生产调用方不能按请求覆盖该路径。 */
    @Autowired
    public SkillPresetService(DemoAdminProperties properties) {
        this(properties.skillPresetRoot());
    }

    /** 测试与离线契约可指向隔离根，仍只能读取三个固定 preset id。 */
    SkillPresetService(Path presetRoot) {
        this.presetRoot = presetRoot.toAbsolutePath().normalize();
    }

    /** 返回三套完整正文与 references，阶段和 reference 均按固定业务顺序排列。 */
    public List<SkillPreset> presets() {
        return PRESET_IDS.stream().map(this::readPreset).toList();
    }

    /** 返回一个固定预置；未知 id 失败关闭，避免 API 演变为任意文件读取器。 */
    public SkillPreset preset(String presetId) {
        if (!PRESET_IDS.contains(presetId)) {
            throw new ServiceException("DEMO_SKILL_PRESET_NOT_FOUND", "内置 Skill 预置不存在");
        }
        return readPreset(presetId);
    }

    /**
     * 把固定预置的三个原始文件复制到一个尚不存在的冻结版本父目录。
     *
     * <p>复制使用文件字节而非重新序列化文本，确保 {@code FrozenSkillLoader.contentHash} 与历史验证快照完全一致。
     */
    public void copyPreset(String presetId, Path targetVersionRoot) {
        Path source = skillRoot(presetId);
        Path target = targetVersionRoot.toAbsolutePath().normalize();
        if (Files.exists(target)) {
            throw new ServiceException("DEMO_FIXTURE_TARGET_EXISTS", "内置 Skill 冻结目标目录已存在");
        }
        try {
            for (String relative : allPaths()) {
                Path sourceFile = requireRegularFile(source.resolve(relative));
                Path targetFile = target.resolve(SKILL_NAME).resolve(relative).normalize();
                if (!targetFile.startsWith(target.resolve(SKILL_NAME))) {
                    throw new ServiceException("DEMO_SKILL_PRESET_INVALID", "内置 Skill 预置路径越界");
                }
                Files.createDirectories(targetFile.getParent());
                Files.copy(sourceFile, targetFile, StandardCopyOption.COPY_ATTRIBUTES);
            }
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceException("DEMO_SKILL_PRESET_COPY_FAILED", "内置 Skill 预置复制失败");
        }
    }

    /** 读取单套完整资产，显式验证固定 frontmatter 名称和两个 reference 文件。 */
    private SkillPreset readPreset(String presetId) {
        Path root = skillRoot(presetId);
        try {
            String markdown = Files.readString(requireRegularFile(root.resolve("SKILL.md")), StandardCharsets.UTF_8);
            if (!markdown.startsWith("---\nname: " + SKILL_NAME + "\n") || !markdown.contains("\n---\n")) {
                throw new ServiceException("DEMO_SKILL_PRESET_INVALID", "内置 Skill 预置 frontmatter 无效");
            }
            List<SkillReference> references = new ArrayList<>();
            for (String relative : REFERENCE_PATHS) {
                references.add(new SkillReference(
                        relative,
                        Files.readString(requireRegularFile(root.resolve(relative)), StandardCharsets.UTF_8)));
            }
            return new SkillPreset(presetId, LABELS.get(presetId), SKILL_NAME, markdown, List.copyOf(references));
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceException("DEMO_SKILL_PRESET_UNREADABLE", "内置 Skill 预置无法读取");
        }
    }

    /** 固定 preset id 解析后再次确认仍处于配置根内。 */
    private Path skillRoot(String presetId) {
        if (!PRESET_IDS.contains(presetId)) {
            throw new ServiceException("DEMO_SKILL_PRESET_NOT_FOUND", "内置 Skill 预置不存在");
        }
        Path root = presetRoot.resolve(presetId).resolve(SKILL_NAME).normalize();
        if (!root.startsWith(presetRoot)) {
            throw new ServiceException("DEMO_SKILL_PRESET_INVALID", "内置 Skill 预置路径越界");
        }
        return root;
    }

    /** 三个文件采用冻结哈希使用的相对路径排序，复制与 API 展示共享同一资产集合。 */
    private static List<String> allPaths() {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of("SKILL.md"), REFERENCE_PATHS.stream())
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    /** 拒绝目录、符号链接和缺失文件，预置损坏时不能产生部分 fixture。 */
    private static Path requireRegularFile(Path path) {
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new ServiceException("DEMO_SKILL_PRESET_INVALID", "内置 Skill 预置文件缺失或无效");
        }
        return path;
    }

    /** 管理端展示的一套完整预置；references 已按固定路径排序且内容不省略。 */
    public record SkillPreset(
            String id, String label, String skillName, String skillMarkdown, List<SkillReference> references) {}
}
