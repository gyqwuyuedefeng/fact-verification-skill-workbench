package com.hsmap.factverification.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 锁定比赛演示材料的最小资产集合，避免现场只能展示空页面或误用真实企业材料。 */
class DemoMaterialContractTest {

    private static final Path DEMO_ROOT = Path.of("../evals/demo-materials");

    /** 三种材料分别覆盖多主张、主体歧义和表格经营指标，并必须清楚标注为虚构数据。 */
    @Test
    void providesThreeClearlyMarkedSimulatedMaterials() throws IOException {
        List<Path> materials = List.of(
                DEMO_ROOT.resolve("01-模拟星河智造经营简报.md"),
                DEMO_ROOT.resolve("02-同名主体核验.txt"),
                DEMO_ROOT.resolve("03-模拟企业经营指标.csv"));

        assertThat(materials).allMatch(Files::isRegularFile);
        for (Path material : materials) {
            String content = Files.readString(material, StandardCharsets.UTF_8);
            assertThat(content).contains("纯模拟").doesNotContain("192.168.", "jdbc:postgresql:");
        }
        assertThat(Files.readString(materials.get(0))).contains("营业收入", "专利", "行政处罚");
        assertThat(Files.readString(materials.get(1))).contains("同名", "统一社会信用代码");
        assertThat(Files.readString(materials.get(2))).contains("年份,营业收入万元,研发投入万元");
    }
}
