package com.hsmap.factverification.compat;

import static org.assertj.core.api.Assertions.assertThatCode;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import org.junit.jupiter.api.Test;

/**
 * 被测试对象：AgentScope 2.0.1 间接使用的 MCP JSON Schema 运行时依赖。
 * 测试目的：防止项目显式依赖旧版 networknt validator，导致 MCP Client 只在打包启动后才抛出类缺失错误。
 * 覆盖范围：MCP SDK 默认校验器的 ServiceLoader 创建链路以及其依赖的 dialect 实现。
 * 前置条件：测试类路径必须与后端可执行包使用同一套 Maven 依赖解析结果。
 */
class McpJsonSchemaRuntimeCompatibilityTest {

    /**
     * 测试场景：构建 Streamable HTTP MCP Client 前创建 SDK 默认 JSON Schema 校验器。
     * 前置条件：AgentScope 固定为 2.0.1，MCP SDK 固定为其传递引入的 0.17.0。
     * 期望结果：默认校验器能够完整实例化，不出现 Dialects 等运行时类缺失。
     * 断言重点：验证真实 ServiceLoader 初始化，而不是只检查某个类名能否编译。
     */
    @Test
    void createsMcpDefaultJsonSchemaValidatorWithResolvedRuntimeDependencies() {
        assertThatCode(JsonSchemaValidator::getDefault).doesNotThrowAnyException();
    }
}

