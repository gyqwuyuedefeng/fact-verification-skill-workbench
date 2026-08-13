package com.hsmap.factverification.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 核验结果入库前的统一失败关闭门禁。
 *
 * <p>JSON Schema 保证基线与 Skill 版本使用相同输出结构；额外的显式业务检查使 VERIFIED 永远不能脱离材料定位和企业外部证据存在。
 */
public final class VerificationResultValidator {

    private final Schema schema;

    /** 从 classpath 加载已经批准并复制到实现模块的结果 schema。 */
    public VerificationResultValidator(ObjectMapper objectMapper, String schemaPath) {
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(schemaPath)) {
            if (input == null) {
                throw new ServiceException("RESULT_SCHEMA_MISSING", "结果契约文件不存在");
            }
            JsonNode schemaNode = objectMapper.readTree(input);
            this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(schemaNode);
        } catch (IOException e) {
            throw new ServiceException("RESULT_SCHEMA_INVALID", "结果契约文件无法读取");
        }
    }

    /** 验证 schema 以及 VERIFIED 必需证据；任何问题都拒绝持久化为正式结果。 */
    public void validate(JsonNode result) {
        List<com.networknt.schema.Error> errors = schema.validate(result);
        if (!errors.isEmpty()) {
            com.networknt.schema.Error first = errors.get(0);
            // 真实模型可能在数十个字段中仅错一处。只暴露实例路径和 schema 关键字，
            // 既能区分“多字段”、“缺字段”和“类型错误”，又不会把模型原文、证据内容或内网信息写入日志。
            String diagnostic = first.getInstanceLocation() + " keyword=" + first.getKeyword();
            throw new ServiceException("RESULT_SCHEMA_INVALID", "核验结果不符合统一输出契约：" + diagnostic);
        }
        for (JsonNode claim : result.path("claims")) {
            if ("VERIFIED".equals(claim.path("status").asText())
                    && (claim.path("materialLocator").isMissingNode()
                            || claim.path("materialLocator").isEmpty()
                            || !claim.path("evidence").isArray()
                            || claim.path("evidence").isEmpty())) {
                throw new ServiceException("VERIFIED_EVIDENCE_REQUIRED", "VERIFIED 主张必须同时包含材料位置和外部证据");
            }
        }
    }
}
