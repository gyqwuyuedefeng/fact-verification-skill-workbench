package com.hsmap.factverification.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.hsmap.factverification.agent.FrozenSkillLoader;
import com.hsmap.factverification.skill.SkillReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 被测试对象：随代码交付的三阶段 Skill 预置资产与 {@link SkillPresetService}。
 * 测试目的：锁定比赛复演使用的初始、优化、回归三份完整 Markdown，并保证管理 API 的返回顺序和文件顺序稳定。
 * 覆盖范围：三套 SKILL.md、两个 references、源快照逐文件摘要、冻结目录 content hash 和复制结果。
 * 前置条件：测试从 backend 模块执行，预置根固定为相邻的 ../skills/presets，禁止依赖被 Git 忽略的 data 运行目录。
 */
class SkillPresetContractTest {

    private static final Path PRESET_ROOT = Path.of("../skills/presets");
    private static final List<String> PRESET_IDS = List.of("01-initial", "02-improved", "03-regression");
    private static final List<String> REFERENCE_PATHS =
            List.of("references/claim-normalization.md", "references/evidence-rules.md");
    private static final Map<String, Map<String, String>> SOURCE_FILE_HASHES = sourceFileHashes();
    private static final Map<String, String> FROZEN_CONTENT_HASHES = Map.of(
            "01-initial", "6ddd7dd413ab70db4c045f64d78aa2c121f96ad0d5ab6ea5f7ac4cb4caf826ff",
            "02-improved", "ac02b84ce4338bfdea243fefaf1c148e4ed90948defaeef5e0a4172fada86154",
            "03-regression", "29b2fbaaf69ddb754212b08e94452ad21574fa93e5f066600688ee1fefce2b1e");

    @TempDir
    Path temporaryRoot;

    /**
     * 测试场景：管理端读取全部三阶段预置内容。
     * 前置条件：每套目录只允许固定 Skill 名和两个按路径排序的 reference 文件。
     * 期望结果：返回 01、02、03 的稳定顺序，每项包含完整正文和完整 reference 内容。
     * 断言重点：不能只返回名称或摘要，前端必须能直接得到三份可展示、可复制的完整 Markdown。
     */
    @Test
    void returnsThreeCompletePresetsInStableOrder() throws Exception {
        SkillPresetService service = new SkillPresetService(PRESET_ROOT);

        List<SkillPresetService.SkillPreset> presets = service.presets();

        assertThat(presets).extracting(SkillPresetService.SkillPreset::id).containsExactlyElementsOf(PRESET_IDS);
        for (SkillPresetService.SkillPreset preset : presets) {
            assertThat(preset.skillName()).isEqualTo("company-material-fact-check");
            assertThat(preset.skillMarkdown())
                    .startsWith("---\nname: company-material-fact-check\n")
                    .contains("\n---\n", "# 企业材料事实核验");
            assertThat(preset.references())
                    .extracting(SkillReference::path)
                    .containsExactlyElementsOf(REFERENCE_PATHS);
            assertThat(preset.references()).allSatisfy(reference -> assertThat(reference.content()).isNotBlank());
        }
    }

    /**
     * 测试场景：核对三套预置是否仍等于经过验证的三个历史快照。
     * 前置条件：源快照位于被忽略的运行目录，契约以逐文件 SHA-256 固化其已核实字节，不在测试时读取 data。
     * 期望结果：九个文件摘要逐一匹配，三目录按现有 FrozenSkillLoader 算法得到已知 content hash。
     * 断言重点：任何正文截断、reference 漏拷贝、换行变化或阶段错位都会导致契约失败。
     */
    @Test
    void preservesAllBytesFromTheThreeVerifiedSourceSnapshots() throws Exception {
        FrozenSkillLoader loader = new FrozenSkillLoader();

        for (String presetId : PRESET_IDS) {
            Path skillRoot = PRESET_ROOT.resolve(presetId).resolve("company-material-fact-check");
            Map<String, String> expected = SOURCE_FILE_HASHES.get(presetId);
            assertThat(sha256(skillRoot.resolve("SKILL.md"))).isEqualTo(expected.get("SKILL.md"));
            for (String reference : REFERENCE_PATHS) {
                assertThat(sha256(skillRoot.resolve(reference))).isEqualTo(expected.get(reference));
            }
            assertThat(loader.contentHash(PRESET_ROOT.resolve(presetId)))
                    .isEqualTo(FROZEN_CONTENT_HASHES.get(presetId));
        }
    }

    /**
     * 测试场景：内置 fixture 请求把某个预置复制到临时冻结版本目录。
     * 前置条件：目标目录尚不存在，调用方只能提交三个固定 preset id 之一。
     * 期望结果：复制后的三文件字节与预置一致，目录 content hash 未发生变化。
     * 断言重点：fixture 应从受版本控制的 preset 实际复制，不能重新拼装或退回 data 运行目录。
     */
    @Test
    void copiesPresetBytesWithoutReconstructingMarkdown() throws Exception {
        SkillPresetService service = new SkillPresetService(PRESET_ROOT);
        Path target = temporaryRoot.resolve("frozen-version");

        service.copyPreset("02-improved", target);

        assertThat(new FrozenSkillLoader().contentHash(target)).isEqualTo(FROZEN_CONTENT_HASHES.get("02-improved"));
        assertThat(Files.readAllBytes(target.resolve("company-material-fact-check/SKILL.md")))
                .isEqualTo(Files.readAllBytes(
                        PRESET_ROOT.resolve("02-improved/company-material-fact-check/SKILL.md")));
    }

    /** 固化三个已核实源快照的逐文件摘要；LinkedHashMap 同时保留报告时的人类可读阶段顺序。 */
    private static Map<String, Map<String, String>> sourceFileHashes() {
        Map<String, Map<String, String>> hashes = new LinkedHashMap<>();
        hashes.put(
                "01-initial",
                Map.of(
                        "SKILL.md", "39f2672d9f9c2dd7ca9dd65c10c58947375919d0c125956a0ef9ab4308ab7ea0",
                        "references/claim-normalization.md",
                                "c69218b6a12dfb0fef29199d263bf3c018cc2bbce54beffd8b214ac45d74b3fa",
                        "references/evidence-rules.md",
                                "51dfbb86443c36b08dfa5c35f99b6f3b7f1627370450413a3cdd4624b188510d"));
        hashes.put(
                "02-improved",
                Map.of(
                        "SKILL.md", "679f745178e22641e3ec2cddb7fc3e756a256a77be111c97ff8c76d2812dfb70",
                        "references/claim-normalization.md",
                                "c69218b6a12dfb0fef29199d263bf3c018cc2bbce54beffd8b214ac45d74b3fa",
                        "references/evidence-rules.md",
                                "51dfbb86443c36b08dfa5c35f99b6f3b7f1627370450413a3cdd4624b188510d"));
        hashes.put(
                "03-regression",
                Map.of(
                        "SKILL.md", "08cc8727a04d1bd20c06059e6d62cc6bbcdc9d38a588ce310b36591f6d1c6187",
                        "references/claim-normalization.md",
                                "c69218b6a12dfb0fef29199d263bf3c018cc2bbce54beffd8b214ac45d74b3fa",
                        "references/evidence-rules.md",
                                "51dfbb86443c36b08dfa5c35f99b6f3b7f1627370450413a3cdd4624b188510d"));
        return Map.copyOf(hashes);
    }

    /** 对单个预置文件按原始字节计算 SHA-256，避免平台默认字符集或换行转换掩盖内容漂移。 */
    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
