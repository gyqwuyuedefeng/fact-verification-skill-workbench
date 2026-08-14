package com.hsmap.factverification.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.evaluation.dataset.GoldDataset;
import com.hsmap.factverification.evaluation.dataset.GoldDatasetLoader;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import com.hsmap.factverification.shared.ServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 被测试对象：{@link GoldDatasetLoader} 与仓库内正式比赛金标文件。
 * 测试目的：锁定三十条门禁、固定顺序、主体 ID 和 MCP 可见人工证据，防止评测数据与真实工具契约漂移。
 * 覆盖范围：合法装载、内容哈希、五类覆盖、真实主体映射、证据来源以及缺失字段失败关闭。
 * 前置条件：临时目录用例不访问外部系统；仓库用例只读取版本控制中的 manifest 和 JSONL。
 */
class DatasetContractTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GoldDatasetLoader loader = new GoldDatasetLoader(objectMapper, new CanonicalJsonHasher(objectMapper));

    /**
     * 测试场景：清单声明三十条字段完整且顺序固定的金标。
     * 前置条件：临时 JSONL 与 manifest 使用同一组 sampleId，但 JSONL 本身不承担排序语义。
     * 期望结果：装载顺序严格服从 manifest，并在重复装载时得到相同 64 位内容哈希。
     * 断言重点：样本数量、顺序、版本和内容识别值必须同时稳定。
     */
    @Test
    void loadsThirtySamplesInManifestOrderWithStableHash() throws Exception {
        List<String> ids = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (int index = 1; index <= 30; index++) {
            String id = "sample-%02d".formatted(index);
            ids.add(id);
            lines.add(sample(id));
        }
        writeDataset(ids, lines, 30);

        GoldDataset first = loader.load(tempDir.resolve("manifest.json"));
        GoldDataset second = loader.load(tempDir.resolve("manifest.json"));

        assertThat(first.samples()).extracting(sample -> sample.sampleId()).containsExactlyElementsOf(ids);
        assertThat(first.contentHash()).hasSize(64).isEqualTo(second.contentHash());
        assertThat(first.version()).isEqualTo("contest-v1");
    }

    /**
     * 测试场景：仓库正式金标作为比赛管理页的唯一评测输入。
     * 前置条件：从 backend 模块目录读取相邻 evals/manifest.json。
     * 期望结果：数据集不少于三十条并覆盖基本资料、财务、知识产权、风险与关系五类事实。
     * 断言重点：主体歧义、数据缺失、期间不匹配和单位不匹配四类关键边界必须保留。
     */
    @Test
    void loadsRepositoryCompetitionDataset() {
        GoldDataset dataset = loader.load(Path.of("../evals/manifest.json"));

        assertThat(dataset.samples()).hasSize(30);
        assertThat(dataset.samples())
                .extracting(sample -> sample.category())
                .contains("BASIC", "FINANCE", "INTELLECTUAL_PROPERTY", "RISK", "RELATIONSHIP");
        assertThat(dataset.samples())
                .flatExtracting(sample -> sample.edgeCases())
                .contains("subject-ambiguity", "data-missing", "period-mismatch", "unit-mismatch");
    }

    /**
     * 测试场景：现场快速评测从正式三十条金标中抽取三个代表样本。
     * 前置条件：快速清单固定为财务正确、否定性风险证据不足、期间金额冲突，最小样本数显式为三。
     * 期望结果：快速集按清单顺序装载，三种结论均覆盖，且每条样本与正式集同标识记录逐字段完全相等。
     * 断言重点：快速入口只能减少样本数量，不能改写材料、金标、证据请求或评分标准形成演示假数据。
     */
    @Test
    void loadsThreeSampleLiveSmokeDatasetAsExactFormalSubset() {
        GoldDataset formal = loader.load(Path.of("../evals/manifest.json"));
        GoldDataset smoke = loader.load(Path.of("../evals/live-smoke-manifest.json"), 3);
        Map<String, com.hsmap.factverification.evaluation.dataset.GoldSample> formalById =
                formal.samples().stream().collect(Collectors.toMap(sample -> sample.sampleId(), sample -> sample));

        assertThat(smoke.version()).isEqualTo("public-tech-live-smoke-v1");
        assertThat(smoke.samples())
                .extracting(sample -> sample.sampleId())
                .containsExactly("iflytek-finance", "iflytek-risk", "yonyou-period-conflict");
        assertThat(smoke.samples())
                .extracting(sample -> sample.expectedStatus())
                .containsExactly("VERIFIED", "INSUFFICIENT", "CONFLICT");
        assertThat(smoke.samples())
                .flatExtracting(sample -> sample.evidenceRequests())
                .extracting(request -> request.toolName())
                .contains("resolve_company", "get_company_financials", "get_company_risks");
        assertThat(smoke.samples())
                .allSatisfy(sample -> assertThat(sample).isEqualTo(formalById.get(sample.sampleId())));
    }

    /**
     * 测试场景：金标主体必须使用六工具返回的内部 companyId，不能误用股票代码。
     * 前置条件：五家评测企业已经通过真实 Streamable HTTP 主体工具完成唯一映射。
     * 期望结果：同一家企业的全部样本都使用本次真实工具复核得到的 32 位内部编码，且数据集升级为不可覆盖的 v4。
     * 断言重点：否则模型严格复制工具证据仍会被评分器判错，评测准确率不具备业务含义。
     */
    @Test
    void repositoryGoldSubjectsMatchLiveEvidenceCompanyIds() {
        GoldDataset dataset = loader.load(Path.of("../evals/manifest.json"));
        Map<String, String> actual = dataset.samples().stream()
                .collect(Collectors.toMap(
                        sample -> sample.expectedSubject().path("companyName").asText(),
                        sample -> sample.expectedSubject().path("companyId").asText(),
                        (left, right) -> {
                            assertThat(right).isEqualTo(left);
                            return left;
                        }));

        assertThat(dataset.version()).isEqualTo("public-tech-2024-v4");
        assertThat(actual)
                .containsAllEntriesOf(Map.of(
                        "科大讯飞股份有限公司", "ef865a606f84bf2dd88486482840eab6",
                        "北京金山办公软件股份有限公司", "5533db99386bc889fc52566b68ad2172",
                        "深信服科技股份有限公司", "bd62524db001604e9a816abd1938434d",
                        "浪潮电子信息产业股份有限公司", "d4a31cf230562c787dd67e171125b462",
                        "用友网络科技股份有限公司", "d08a6dee3c5fd0859f97d393affa5c4a"));
    }

    /**
     * 测试场景：旧 v3 中已经无法由当前只读企业证据链复现的八条金标完成版本化修订。
     * 前置条件：当前 ES 已把浪潮规范主体收敛到新 companyId，风险工具因索引缺失对正向处罚主张返回空结果。
     * 期望结果：六条浪潮样本统一使用当前主体；财务和知识产权支持性主张可复现，两个处罚存在性主张失败关闭为证据不足。
     * 断言重点：这里只允许调整已实际取证确认的漂移字段，不能为了制造版本差距而改变未漂移样本或写入 Skill 提示。
     */
    @Test
    void formalV4AlignsEightDriftedSamplesWithCurrentReadOnlyEvidence() {
        GoldDataset dataset = loader.load(Path.of("../evals/manifest.json"));
        Map<String, com.hsmap.factverification.evaluation.dataset.GoldSample> samples =
                dataset.samples().stream().collect(Collectors.toMap(sample -> sample.sampleId(), sample -> sample));

        assertThat(List.of(
                        "inspur-basic",
                        "inspur-finance",
                        "inspur-ip",
                        "inspur-risk",
                        "inspur-relation",
                        "inspur-unit-conflict"))
                .allSatisfy(sampleId -> assertThat(samples.get(sampleId).expectedSubject().path("companyId").asText())
                        .isEqualTo("d4a31cf230562c787dd67e171125b462"));
        assertThat(samples.get("inspur-finance").material().path("text").asText())
                .startsWith("浪潮电子信息产业股份有限公司")
                .doesNotContain("浪潮信息 2024");
        assertThat(samples.get("inspur-finance").expectedStatus()).isEqualTo("VERIFIED");
        assertThat(samples.get("inspur-finance").manualEvidence().get(0).recordId())
                .isEqualTo("e650b6c0923f2b69b3955a90720c2302");
        assertThat(samples.get("inspur-ip").expectedStatus()).isEqualTo("VERIFIED");
        assertThat(samples.get("inspur-ip").manualEvidence().get(0).recordId())
                .isEqualTo("3f5ad9ca859fdfe7e8884e0951f293e4");
        assertThat(samples.get("inspur-relation").manualEvidence().get(0).recordId())
                .isEqualTo("82c155ba1d55d87a1ca9756f8c863d9b");
        assertThat(samples.get("sangfor-risk").expectedStatus()).isEqualTo("INSUFFICIENT");
        assertThat(samples.get("yonyou-risk").expectedStatus()).isEqualTo("INSUFFICIENT");
        assertThat(samples.get("sangfor-risk").manualEvidence().get(0).dataset())
                .isEqualTo("mcp-query-scope");
        assertThat(samples.get("yonyou-risk").manualEvidence().get(0).dataset())
                .isEqualTo("mcp-query-scope");
    }

    /**
     * 测试场景：正式金标的支持性结论必须能由本期六个只读 MCP 工具复查。
     * 前置条件：VERIFIED/CONFLICT 样本必须引用十二索引白名单之一；INSUFFICIENT 可引用固定查询范围审计记录。
     * 期望结果：不存在仅引用外部年报但 Agent 工具不可见的支持性金标。
     * 断言重点：人工证据的数据集与 recordId 都必须能映射到实际 MCP 查询或查询范围快照。
     */
    @Test
    void repositoryGoldEvidenceIsVisibleToMcpTools() {
        GoldDataset dataset = loader.load(Path.of("../evals/manifest.json"));
        Set<String> liveDatasets = Set.of(
                "ads_lget_company_info",
                "ads_lget_company_revenue",
                "ads_lget_patent_info",
                "ads_lget_software_copyright",
                "ads_lget_product_info",
                "ads_lget_company_lose_trust",
                "ads_lget_company_illegal_info",
                "ads_lget_company_adm_punish",
                "ads_lget_company_tax_arrears_info",
                "ads_lget_company_sholder",
                "ads_lget_company_client",
                "ads_lget_company_supply");

        dataset.samples().forEach(sample -> sample.manualEvidence().forEach(evidence -> {
            if ("INSUFFICIENT".equals(sample.expectedStatus())) {
                assertThat(evidence.dataset()).isIn("mcp-query-scope");
            } else {
                assertThat(evidence.dataset()).isIn(liveDatasets);
            }
            assertThat(evidence.recordId()).isNotBlank();
        }));
        assertThat(dataset.samples()).allSatisfy(sample -> assertThat(sample.evidenceRequests())
                .extracting(request -> request.toolName())
                .contains("resolve_company", sample.manualEvidence().get(0).toolName()));
    }

    /**
     * 测试场景：门禁清单声明的样本数低于赛题要求。
     * 前置条件：临时文件只包含一条其他字段合法的样本。
     * 期望结果：装载器失败关闭并给出不少于三十条的明确原因。
     * 断言重点：不能用小型冒烟集冒充正式比赛金标。
     */
    @Test
    void rejectsDatasetBelowThirtySamples() throws Exception {
        writeDataset(List.of("sample-01"), List.of(sample("sample-01")), 1);

        assertThatThrownBy(() -> loader.load(tempDir.resolve("manifest.json")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("至少 30 条");
    }

    /**
     * 测试场景：三十条清单中的最后一条缺少人工证据。
     * 前置条件：其余样本和 manifest 数量、顺序均合法，隔离唯一缺陷。
     * 期望结果：装载器拒绝整个数据集并指出人工证据缺失。
     * 断言重点：不允许不完整样本静默进入准确率分母。
     */
    @Test
    void rejectsMissingSampleAndRequiredGoldFields() throws Exception {
        List<String> ids = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (int index = 1; index <= 30; index++) {
            String id = "sample-%02d".formatted(index);
            ids.add(id);
            lines.add(index == 30 ? incompleteSample(id) : sample(id));
        }
        writeDataset(ids, lines, 30);

        assertThatThrownBy(() -> loader.load(tempDir.resolve("manifest.json")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("人工证据");
    }

    /** 将临时金标和清单一次写入测试隔离目录，供失败关闭及哈希用例复用。 */
    private void writeDataset(List<String> ids, List<String> lines, int declaredCount) throws Exception {
        Files.write(tempDir.resolve("dataset.jsonl"), lines);
        objectMapper.writeValue(
                tempDir.resolve("manifest.json").toFile(),
                java.util.Map.of(
                        "version", "contest-v1",
                        "datasetFile", "dataset.jsonl",
                        "sampleCount", declaredCount,
                        "sampleIds", ids,
                        "license", "内部比赛评测授权"));
    }

    /** 返回字段完整的最小财务金标文本，调用方只替换 sampleId。 */
    private static String sample(String id) {
        return """
                {"sampleId":"%s","category":"FINANCE","material":{"text":"示例公司 2024 年营收为 100 万元","locator":{"type":"LINE","value":"L1"}},"expectedSubject":{"companyId":"company-1","companyName":"示例公司"},"normalizedClaim":{"metric":"revenue","period":"2024","operator":"EQUALS","value":"100","unit":"万元"},"expectedStatus":"VERIFIED","evidenceRequests":[{"toolName":"resolve_company","arguments":{"query":"示例公司"}},{"toolName":"get_company_financials","arguments":{"companyId":"company-1"}}],"manualEvidence":[{"toolName":"get_company_financials","dataset":"authorized-fixture","recordId":"record-1","retrievedAt":"2026-08-12T00:00:00Z","sourceUrl":"https://example.com/report.pdf","sourceLocator":"P1"}],"acceptableCriteria":{"period":"2024","unit":"万元"},"edgeCases":[]}
                """
                .formatted(id)
                .trim();
    }

    /** 从合法样本删除人工证据，构造装载器必须拒绝的单一缺陷输入。 */
    private static String incompleteSample(String id) {
        return sample(id)
                .replace(
                        "\"manualEvidence\":[{\"toolName\":\"get_company_financials\",\"dataset\":\"authorized-fixture\",\"recordId\":\"record-1\",\"retrievedAt\":\"2026-08-12T00:00:00Z\",\"sourceUrl\":\"https://example.com/report.pdf\",\"sourceLocator\":\"P1\"}]",
                        "\"manualEvidence\":[]");
    }
}
