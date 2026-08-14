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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 使用 Spring RestClient 向固定索引发送 `_search`。
 *
 * <p>仅构造查询请求，不包含任何 ES 写 API；连接地址和凭据只从环境配置读取。
 */
@Component
public final class EsEvidenceQuery implements LiveEvidenceQuery {

    private static final Logger LOGGER = LoggerFactory.getLogger(EsEvidenceQuery.class);

    private final List<RestClient> restClients;

    public EsEvidenceQuery(EnterpriseEvidenceProperties properties) {
        this.restClients = buildClients(properties.elasticsearch());
    }

    /** 按工具绑定的索引顺序聚合命中并生成统一 envelope。 */
    @Override
    public Object query(EvidenceToolName toolName, Map<String, Object> arguments) {
        if (restClients.isEmpty()) {
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
        for (int index = 0; index < restClients.size(); index++) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restClients
                        .get(index)
                        .post()
                        .uri("/{index}/_search", policy.indexName())
                        .body(body)
                        .retrieve()
                        .body(Map.class);
                return toPage(response, policy, observedAt);
            } catch (ResourceAccessException exception) {
                if (hasNextNode(index)) {
                    logNodeFailure(index, "CONNECTION", "RETRY_NEXT");
                    continue;
                }
                logNodeFailure(index, "CONNECTION", "FAIL");
                throw queryFailed();
            } catch (RestClientResponseException exception) {
                if (isMissingApprovedIndex(exception)) {
                    LOGGER.info(
                            "企业证据固定索引当前未部署：nodeOrdinal={}, category=INDEX_MISSING, action=EMPTY_EVIDENCE",
                            index + 1);
                    return new SearchPage(List.of(), List.of(), 0);
                }
                if (exception.getStatusCode().is5xxServerError() && hasNextNode(index)) {
                    logNodeFailure(index, "HTTP_5XX", "RETRY_NEXT");
                    continue;
                }
                String category = exception.getStatusCode().is5xxServerError() ? "HTTP_5XX" : "HTTP_4XX";
                logNodeFailure(index, category, "FAIL");
                throw queryFailed();
            } catch (RestClientException exception) {
                logNodeFailure(index, "CLIENT", "FAIL");
                throw queryFailed();
            }
        }
        throw queryFailed();
    }

    /**
     * 仅识别 Elasticsearch 明确返回的缺索引错误；代理路径错误、认证失败和其他 404 仍按查询失败处理。
     *
     * <p>固定白名单索引未部署代表当前数据源不能提供该类证据，上层必须据此给出证据不足或人工介入，不能把它解释为事实不存在。
     */
    private static boolean isMissingApprovedIndex(RestClientResponseException exception) {
        if (exception.getStatusCode().value() != 404) {
            return false;
        }
        String responseBody = exception.getResponseBodyAsString();
        return responseBody != null && responseBody.contains("index_not_found_exception");
    }

    /**
     * 只在当前配置后面仍有节点时重试，保持地址顺序可解释且不引入额外负载均衡状态。
     */
    private boolean hasNextNode(int index) {
        return index + 1 < restClients.size();
    }

    /**
     * 现场日志只暴露节点序号、错误类别与动作，禁止写入地址、响应体、底层异常或认证信息。
     */
    private static void logNodeFailure(int index, String category, String action) {
        LOGGER.warn("企业证据 ES 节点调用失败：nodeOrdinal={}, category={}, action={}", index + 1, category, action);
    }

    /** 对所有底层客户端失败提供同一稳定业务边界，避免节点与凭据信息泄漏给 Agent。 */
    private static ServiceException queryFailed() {
        return new ServiceException("ES_QUERY_FAILED", "企业证据查询暂不可用");
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
            body.put("sort", List.of(Map.of("report_year", Map.of("order", "desc", "missing", "_last"))));
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

    /**
     * 为每个非空地址构造独立客户端。凭据仍来自同一环境配置，节点列表只承担瞬时连接与 5xx 容错，
     * 不会在认证失败时改试其他节点。
     */
    private static List<RestClient> buildClients(EnterpriseEvidenceProperties.Elasticsearch properties) {
        if (properties.addresses() == null || properties.addresses().isEmpty()) {
            return List.of();
        }
        List<RestClient> clients = new ArrayList<>();
        for (String configuredAddress : properties.addresses()) {
            if (configuredAddress == null || configuredAddress.isBlank()) {
                continue;
            }
            String address = configuredAddress.strip();
            String baseUrl = address.startsWith("http://") || address.startsWith("https://")
                    ? address
                    : properties.scheme() + "://" + address;
            RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
            if (properties.username() != null && !properties.username().isBlank()) {
                String password = properties.password() == null ? "" : properties.password();
                String token = Base64.getEncoder()
                        .encodeToString((properties.username() + ":" + password).getBytes(StandardCharsets.UTF_8));
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + token);
            }
            clients.add(builder.build());
        }
        return List.copyOf(clients);
    }

    private record SearchPage(
            List<Map<String, Object>> items, List<EsEvidenceEnvelope.EvidenceReference> references, long total) {}
}
