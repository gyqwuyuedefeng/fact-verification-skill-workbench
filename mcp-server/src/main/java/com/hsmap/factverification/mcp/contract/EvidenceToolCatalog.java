package com.hsmap.factverification.mcp.contract;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 十二个批准 ES 索引及字段白名单。
 *
 * <p>索引、字段、排序和上限均由服务端固定，模型输入永远不能成为索引名或 `_source` 字段。
 */
public final class EvidenceToolCatalog {

    private static final Map<EvidenceToolName, List<IndexPolicy>> POLICIES = policies();

    private EvidenceToolCatalog() {}

    /** 返回指定工具的固定查询策略。 */
    public static List<IndexPolicy> policiesFor(EvidenceToolName toolName) {
        return POLICIES.get(toolName);
    }

    /** 返回全部索引，用于权限核对和静态门禁。 */
    public static Set<String> allowedIndices() {
        Set<String> indices = new LinkedHashSet<>();
        indexPolicies().forEach(policy -> indices.add(policy.indexName()));
        return Set.copyOf(indices);
    }

    /** 返回按工具声明顺序展开的全部策略。 */
    public static List<IndexPolicy> indexPolicies() {
        return POLICIES.values().stream().flatMap(List::stream).distinct().toList();
    }

    private static Map<EvidenceToolName, List<IndexPolicy>> policies() {
        Map<EvidenceToolName, List<IndexPolicy>> values = new EnumMap<>(EvidenceToolName.class);
        IndexPolicy company = policy(
                "ads_lget_company_info",
                10,
                "company_code",
                "company_name",
                "company_sname",
                "name_before",
                "uni_code",
                "legal_rep",
                "found_date",
                "regcapital_amt_cal",
                "business_state",
                "reg_address",
                "business_scope",
                "industry_nea");
        values.put(EvidenceToolName.RESOLVE_COMPANY, List.of(company));
        values.put(EvidenceToolName.GET_COMPANY_PROFILE, List.of(company.withLimit(1)));
        values.put(
                EvidenceToolName.GET_COMPANY_FINANCIALS,
                List.of(policy(
                        "ads_lget_company_revenue",
                        10,
                        "company_code",
                        "report_year",
                        "total_sales",
                        "total_sales_level",
                        "total_sales_yoy",
                        "retained_profit",
                        "retained_profit_level",
                        "retained_profit_yoy",
                        "retained_operating_income_yoy",
                        "liability_assets_yoy")));
        values.put(
                EvidenceToolName.GET_COMPANY_INTELLECTUAL_PROPERTY,
                List.of(
                        policy(
                                "ads_lget_patent_info",
                                20,
                                "application_code",
                                "publication_code",
                                "publication_date",
                                "application_date",
                                "patent_type",
                                "legal_state",
                                "title",
                                "applicant_now",
                                "org_info_list"),
                        policy(
                                "ads_lget_software_copyright",
                                20,
                                "reg_no",
                                "approve_date",
                                "publish_date",
                                "sw_fname",
                                "sw_sname",
                                "software_type",
                                "org_info_list"),
                        policy(
                                "ads_lget_product_info",
                                20,
                                "product_name",
                                "product_serve",
                                "spec_model",
                                "cert_no",
                                "aprv_date",
                                "end_date",
                                "org_info_list")));
        values.put(
                EvidenceToolName.GET_COMPANY_RISKS,
                List.of(
                        policy(
                                "ads_lget_company_lose_trust",
                                20,
                                "company_code",
                                "company_name",
                                "filing_date",
                                "publish_date",
                                "execute_state",
                                "execute_num",
                                "court",
                                "content"),
                        policy(
                                "ads_lget_company_illegal_info",
                                20,
                                "company_code",
                                "in_org",
                                "in_reason",
                                "in_date",
                                "out_reason",
                                "out_date",
                                "illegal_type"),
                        policy(
                                "ads_lget_company_adm_punish",
                                20,
                                "company_code",
                                "punish_num",
                                "punish_reason",
                                "punish_result",
                                "punish_org_name",
                                "punish_date",
                                "punish_state"),
                        policy(
                                "ads_lget_company_tax_arrears_info",
                                20,
                                "company_code",
                                "tax_arrears",
                                "tax_arrears_bal",
                                "curr_tax_arrears_bal",
                                "tax_arrears_occur_date",
                                "publish_date",
                                "org_name")));
        values.put(
                EvidenceToolName.GET_COMPANY_RELATIONSHIPS,
                List.of(
                        policy(
                                "ads_lget_company_sholder",
                                20,
                                "company_code",
                                "company_name",
                                "sholder_name",
                                "sholder_type",
                                "proportion",
                                "con_date",
                                "con_amt"),
                        policy(
                                "ads_lget_company_client",
                                20,
                                "company_code",
                                "company_name",
                                "client_company_code",
                                "client_company_name",
                                "pub_date",
                                "proportion",
                                "sales_amt",
                                "sales_product_name"),
                        policy(
                                "ads_lget_company_supply",
                                20,
                                "company_name",
                                "supp_company_code",
                                "supp_company_name",
                                "pub_date",
                                "proportion",
                                "supp_amt",
                                "supp_product_name")));
        return Map.copyOf(values);
    }

    private static IndexPolicy policy(String indexName, int limit, String... fields) {
        return new IndexPolicy(indexName, Set.of(fields), limit);
    }

    /** 单索引固定 `_source` 字段与最大返回条数。 */
    public record IndexPolicy(String indexName, Set<String> sourceFields, int limit) {
        public IndexPolicy {
            sourceFields = Set.copyOf(sourceFields);
        }

        private IndexPolicy withLimit(int newLimit) {
            return new IndexPolicy(indexName, sourceFields, newLimit);
        }
    }
}
