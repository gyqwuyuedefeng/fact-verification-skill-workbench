package com.hsmap.factverification.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.compat.AgentScopeRuntimeCompatibility;
import com.hsmap.factverification.config.WorkbenchProperties;
import com.hsmap.factverification.shared.ServiceException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 使用现有公司千问 OpenAI-compatible 配置生成版本改动摘要。 */
@Component
public class OpenAiSkillChangeSummaryClient implements SkillChangeSummaryClient {

    private final WorkbenchProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAiSkillChangeSummaryClient(WorkbenchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回当前 OpenAI-compatible 配置中的模型标识。
     *
     * <p>比较服务把该值随成功结果冻结，避免后续环境变量调整后无法追溯历史升级说明的模型来源。
     *
     * @return 当前工作台配置的模型 ID
     */
    @Override
    public String modelId() {
        return properties.model().id();
    }

    /** 请求只用于审核解释；任何网络或 JSON 错误都由比较服务降级，不向门禁传播。 */
    @Override
    public GeneratedChangeSummary summarize(String baseContent, String targetContent) {
        WorkbenchProperties.Model model = properties.model();
        var endpoint = AgentScopeRuntimeCompatibility.resolveModelEndpoint(model.url(), model.endpointPath());
        // 单人 MVP 只在管理员手动点击时发起一次请求，不依赖容器额外装配可变的 HTTP Builder。
        RestClient client = RestClient.builder().baseUrl(endpoint.baseUrl()).build();
        Map<String, Object> body = Map.of(
                "model", model.id(),
                "temperature", 0,
                "messages",
                        List.of(
                                Map.of(
                                        "role",
                                        "system",
                                        "content",
                                        "你是Skill版本审核助手。只比较提供的两份内容，返回纯JSON："
                                                + "{\"headline\":\"一句话\",\"changes\":[\"改动\"],\"reviewRisks\":[\"风险\"]}。"
                                                + "不得判断是否发布。"),
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        "基准版本：\n" + baseContent + "\n\n目标版本：\n" + targetContent)));
        String response = client.post()
                .uri(endpoint.endpointPath())
                .headers(headers -> addAuthorization(headers, model.apiKey()))
                .body(body)
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            String content =
                    root.path("choices").path(0).path("message").path("content").asText();
            JsonNode summary = objectMapper.readTree(extractJson(content));
            return new GeneratedChangeSummary(
                    summary.path("headline").asText(),
                    objectMapper.convertValue(
                            summary.path("changes"),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)),
                    objectMapper.convertValue(
                            summary.path("reviewRisks"),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        } catch (Exception exception) {
            throw new ServiceException("MODEL_SUMMARY_INVALID", "模型升级说明格式无效");
        }
    }

    private static void addAuthorization(HttpHeaders headers, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey);
        }
    }

    private static String extractJson(String value) {
        String text = value == null ? "" : value.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return text;
    }
}
