package com.hsmap.factverification.mcp.query;

import com.hsmap.factverification.mcp.config.EnterpriseEvidenceProperties;
import com.hsmap.factverification.mcp.contract.EvidenceToolCatalog;
import com.hsmap.factverification.mcp.contract.EvidenceToolCatalog.IndexPolicy;
import com.hsmap.factverification.mcp.contract.EvidenceToolName;
import com.hsmap.factverification.mcp.shared.ServiceException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 使用 Spring RestClient 向固定索引发送 `_search`。
 *
 * <p>仅构造查询请求，不包含任何 ES 写 API；连接地址和凭据只从环境配置读取。
 */
@Component
public final class EsEvidenceQuery implements LiveEvidenceQuery {

    private final RestClient restClient;

    public EsEvidenceQuery(EnterpriseEvidenceProperties properties) {
        this.restClient = buildClient(properties.elasticsearch());
    }

    /** 按工具绑定的索引顺序聚合命中并生成统一 envelope。 */
    @Override
    public Object query(EvidenceToolName toolName, Map<String, Object> arguments) {
        if (restClient == null) {
            throw new ServiceException("ES_NOT_CONFIGURED", "企业证据地址未配置");
        }
        OffsetDateTime observedAt = OffsetDateTime.now(ZoneOffset.UTC);
        List<Map<String, Object>> items = new ArrayList<>();
        List<EsEvidenceEnvelope.EvidenceReference> references = new ArrayList<>();
        long total = 0;
        boolean truncated = false;
        for (IndexPolicy policy : EvidenceToolCatalog.policiesFor(toolName)) {
            SearchPage page = search(toolName, policy, arguments, observedAt);
            items.addAll(page.items());
            references.addAll(page.references());
            total += page.total();
            truncated = truncated || page.total() > page.items().size();
        }
        String companyId = String.valueOf(arguments.values().iterator().next());
        EsEvidenceEnvelope.CompanySubject subject = subject(toolName, companyId, items);
        return new EsEvidenceEnvelope(subject, observedAt, items, total, truncated, references);
    }

    private SearchPage search(
            EvidenceToolName toolName, IndexPolicy policy, Map<String, Object> arguments, OffsetDateTime observedAt) {
        Map<String, Object> body = queryBody(toolName, policy, arguments);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient
                    .post()
                    .uri("/{index}/_search", policy.indexName())
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return toPage(response, policy, observedAt);
        } catch (RestClientException exception) {
            throw new ServiceException("ES_QUERY_FAILED", "企业证据查询暂不可用");
        }
    }

    private static Map<String, Object> queryBody(
            EvidenceToolName toolName, IndexPolicy policy, Map<String, Object> arguments) {
        String value = String.valueOf(arguments.values().iterator().next());
        Map<String, Object> query;
        if (toolName == EvidenceToolName.RESOLVE_COMPANY) {
            query = Map.of(
                    "bool",
                    Map.of(
                            "should",
                            List.of(
                                    Map.of("match_phrase", Map.of("company_name", value)),
                                    Map.of("match_phrase", Map.of("name_before", value)),
                                    Map.of("term", Map.of("company_sname.keyword", value)),
                                    Map.of("term", Map.of("uni_code.keyword", value))),
                            "minimum_should_match",
                            1));
        } else if (policy.indexName().equals("ads_lget_patent_info")
                || policy.indexName().equals("ads_lget_software_copyright")
                || policy.indexName().equals("ads_lget_product_info")) {
            query = Map.of(
                    "nested",
                    Map.of(
                            "path",
                            "org_info_list",
                            "query",
                            Map.of("term", Map.of("org_info_list.company_code.keyword", value))));
        } else {
            query = Map.of("bool", Map.of("filter", List.of(Map.of("term", Map.of("company_code.keyword", value)))));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", policy.limit());
        body.put("track_total_hits", true);
        body.put("_source", policy.sourceFields());
        body.put("query", query);
        if (policy.indexName().equals("ads_lget_company_revenue")) {
            // 财务索引总量常高于工具固定的十条返回上限。十条足以覆盖当前比赛材料使用的2020年至今报告期，
            // 同时避免把整个企业历史无界塞进模型上下文。若不指定排序，不同 ES 分片可能在两次评测中返回
            // 不同年份，导致同一金标的证据覆盖随机变化；固定最新报告期优先，且不允许模型输入排序字段。
            body.put("sort", List.of(Map.of(
                    "report_year", Map.of("order", "desc", "missing", "_last"))));
        }
        return Map.copyOf(body);
    }

    private static SearchPage toPage(Map<String, Object> response, IndexPolicy policy, OffsetDateTime observedAt) {
        if (response == null || !(response.get("hits") instanceof Map<?, ?> hitsRoot)) {
            throw new ServiceException("ES_RESPONSE_INVALID", "企业证据响应格式无效");
        }
        long total = total(hitsRoot.get("total"));
        List<Map<String, Object>> items = new ArrayList<>();
        List<EsEvidenceEnvelope.EvidenceReference> references = new ArrayList<>();
        Object rawHits = hitsRoot.get("hits");
        if (rawHits instanceof List<?> hits) {
            for (Object rawHit : hits) {
                if (!(rawHit instanceof Map<?, ?> hit)) {
                    continue;
                }
                Object sourceValue = hit.get("_source");
                if (!(sourceValue instanceof Map<?, ?> source)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                source.forEach((key, value) -> item.put(String.valueOf(key), value));
                item.put("dataset", policy.indexName());
                String recordId = String.valueOf(hit.get("_id"));
                item.put("recordId", recordId);
                // 公司 dev ES 的白名单字段允许为 null（例如简称、曾用名）。Map.copyOf 会拒绝这些合法
                // 数据，因此这里保留 null 并用防御性副本维持只读证据边界，避免下游改写原始命中。
                items.add(Collections.unmodifiableMap(new LinkedHashMap<>(item)));
                references.add(new EsEvidenceEnvelope.EvidenceReference(
                        "HS_ENTERPRISE_ES", policy.indexName(), recordId, observedAt));
            }
        }
        return new SearchPage(items, references, total);
    }

    private static long total(Object total) {
        if (total instanceof Number number) {
            return number.longValue();
        }
        if (total instanceof Map<?, ?> totalMap && totalMap.get("value") instanceof Number number) {
            return number.longValue();
        }
        return 0;
    }

    private static EsEvidenceEnvelope.CompanySubject subject(
            EvidenceToolName toolName, String requested, List<Map<String, Object>> items) {
        if (items.isEmpty()) {
            return toolName == EvidenceToolName.RESOLVE_COMPANY
                    ? null
                    : new EsEvidenceEnvelope.CompanySubject(requested, requested, null);
        }
        Map<String, Object> first = items.get(0);
        String companyId = stringOr(first.get("company_code"), requested);
        String companyName = stringOr(first.get("company_name"), companyId);
        String unifiedCode = first.get("uni_code") == null ? null : String.valueOf(first.get("uni_code"));
        return new EsEvidenceEnvelope.CompanySubject(companyId, companyName, unifiedCode);
    }

    private static String stringOr(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static RestClient buildClient(EnterpriseEvidenceProperties.Elasticsearch properties) {
        if (properties.addresses() == null
                || properties.addresses().isEmpty()
                || properties.addresses().get(0) == null
                || properties.addresses().get(0).isBlank()) {
            return null;
        }
        String address = properties.addresses().get(0).strip();
        String baseUrl = address.startsWith("http://") || address.startsWith("https://")
                ? address
                : properties.scheme() + "://" + address;
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        if (properties.username() != null && !properties.username().isBlank()) {
            String token = Base64.getEncoder()
                    .encodeToString(
                            (properties.username() + ":" + properties.password()).getBytes(StandardCharsets.UTF_8));
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + token);
        }
        return builder.build();
    }

    private record SearchPage(
            List<Map<String, Object>> items, List<EsEvidenceEnvelope.EvidenceReference> references, long total) {}
}
