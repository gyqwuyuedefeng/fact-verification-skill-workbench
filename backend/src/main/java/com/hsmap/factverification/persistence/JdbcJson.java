package com.hsmap.factverification.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.shared.ServiceException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 统一处理 PostgreSQL jsonb 的序列化，确保所有仓储都使用同一 JSON 口径。 */
@Component
public final class JdbcJson {

    private final ObjectMapper objectMapper;

    /** 复用 Spring 管理的 ObjectMapper，避免另建一套日期或属性命名规则。 */
    public JdbcJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 把业务对象转换为可绑定到 `?::jsonb` 的文本，不在异常中暴露原始内容。 */
    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("JSON_SERIALIZATION_FAILED", "持久化 JSON 生成失败");
        }
    }

    /** 解析评测变体标识；仅提取字符串，忽略报告中的其他展示字段。 */
    public Set<String> readVariantIdentifiers(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            Set<String> identifiers = new LinkedHashSet<>();
            if (root.isArray()) {
                root.forEach(node -> addIdentifier(identifiers, node));
            }
            return Set.copyOf(identifiers);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("PERSISTED_JSON_INVALID", "数据库中的评测变体格式无效");
        }
    }

    private static void addIdentifier(Set<String> identifiers, JsonNode node) {
        if (node.isTextual()) {
            identifiers.add(node.asText());
            return;
        }
        JsonNode identifier = node.get("identifier");
        if (identifier == null) {
            identifier = node.get("versionId");
        }
        if (identifier != null && identifier.isTextual()) {
            identifiers.add(identifier.asText());
        }
    }
}
