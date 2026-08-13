package com.hsmap.factverification.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 被测试对象：比赛项目随代码交付的 company-material-fact-check Skill 源资产。
 * 测试目的：锁定公司 skill-template 所要求的元数据形状，并确保正文明确声明两个 references 的适用职责。
 * 覆盖范围：SKILL.md frontmatter、六工具清单、references 路由，以及主体消歧、金额归一化和否定性风险规则。
 * 前置条件：测试从 backend 模块运行，Skill 源目录固定为相邻的 ../skills/company-material-fact-check。
 */
class SkillAssetContractTest {

    private static final Path SKILL_ROOT = Path.of("../skills/company-material-fact-check");

    /**
     * 测试场景：管理员从当前源目录创建一个全新根草稿或后续草稿资产。
     * 前置条件：SKILL.md 必须与公司模板的元数据字段对齐，同时保持本比赛唯一 Skill 名称和六个只读工具。
     * 期望结果：frontmatter 包含模板字段，正文把详细规则路由到两个 references，而不是复制成第二套规则。
     * 断言重点：新草稿的源资产可识别、可展示，并能明确约束事实核验所需工具与参考文件。
     */
    @Test
    void followsCompanyTemplateAndRoutesDetailedRulesToReferences() throws Exception {
        String skill = Files.readString(SKILL_ROOT.resolve("SKILL.md"));

        assertThat(skill)
                .startsWith("---\nname: company-material-fact-check\n")
                .contains("\nslug: company-material-fact-check\n")
                .contains("\nmodel: company-configured-qwen\n")
                .contains("\naliases:\n")
                .contains("\nwhen_to_use:")
                .contains("\nargument_hint:")
                .contains("\ntools:\n")
                .contains("  - resolve_company\n")
                .contains("  - get_company_relationships\n")
                .contains("\nparams:\n")
                .contains("references/claim-normalization.md")
                .contains("references/evidence-rules.md");
    }

    /**
     * 测试场景：同一 Skill 处理主体简称、不同金额单位和“无风险”否定性表述。
     * 前置条件：详细领域规则分别保存在两个 reference 文件，并由冻结加载器确定性注入。
     * 期望结果：主体规则要求消歧，金额规则给出固定换算关系，风险规则区分“未查到记录”和“确认不存在”。
     * 断言重点：三个测试草稿所代表的改进方向都有真实可执行规则，而不是仅有变更说明标题。
     */
    @Test
    void providesRulesForThreeDraftTestingThemes() throws Exception {
        String normalization = Files.readString(SKILL_ROOT.resolve("references/claim-normalization.md"));
        String evidence = Files.readString(SKILL_ROOT.resolve("references/evidence-rules.md"));

        assertThat(normalization)
                .contains("简称")
                .contains("唯一主体")
                .contains("1 亿元 = 100000000 元")
                .contains("1 万元 = 10000 元");
        assertThat(evidence)
                .contains("否定性风险主张")
                .contains("未检索到记录")
                .contains("不能直接判定为 VERIFIED");
    }
}
