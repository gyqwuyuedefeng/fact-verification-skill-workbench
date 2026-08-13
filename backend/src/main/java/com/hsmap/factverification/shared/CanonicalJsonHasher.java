package com.hsmap.factverification.shared;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 为数据集、运行条件、Skill 内容和工具请求生成可复现的规范 JSON SHA-256。 */
public final class CanonicalJsonHasher {

    private final ObjectMapper canonicalMapper;

    /** 复制调用方 mapper，避免为生成识别值而改变整个应用的 JSON 序列化行为。 */
    public CanonicalJsonHasher(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper
                .copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /** 将对象转为字段顺序稳定的紧凑 JSON 后计算小写十六进制 SHA-256。 */
    public String hash(Object value) {
        try {
            byte[] canonicalBytes = canonicalMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalBytes));
        } catch (JsonProcessingException e) {
            throw new ServiceException("CANONICAL_JSON_INVALID", "无法规范化待识别内容");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256", e);
        }
    }
}
