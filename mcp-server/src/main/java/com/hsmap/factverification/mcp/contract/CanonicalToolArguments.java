package com.hsmap.factverification.mcp.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** 为只有一个字符串字段的六工具参数生成跨模块一致的规范 JSON hash。 */
public final class CanonicalToolArguments {

    private CanonicalToolArguments() {}

    /** 参数已完成长度和字符校验，此处只做 JSON 转义与 SHA-256。 */
    public static String sha256(Map<String, Object> arguments) {
        Map.Entry<String, Object> entry = arguments.entrySet().iterator().next();
        String canonical =
                "{\"" + jsonEscape(entry.getKey()) + "\":\"" + jsonEscape(String.valueOf(entry.getValue())) + "\"}";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256", exception);
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
