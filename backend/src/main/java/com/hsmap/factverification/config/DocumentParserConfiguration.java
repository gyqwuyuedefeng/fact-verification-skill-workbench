package com.hsmap.factverification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.document.DeterministicDocumentParser;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 组装无网络副作用的确定性解析器与公共 hash 组件。 */
@Configuration
public class DocumentParserConfiguration {

    /**
     * 为 AgentScope 2.0.1 与现有 JSON Schema 组件提供 Jackson 2 映射器。
     *
     * <p>Spring Boot 4 默认切换到 Jackson 3，不再自动创建 {@code com.fasterxml.jackson} 类型的 Bean；本项目的
     * AgentScope 适配和持久化边界仍使用 Jackson 2。这里显式注册单个公共实例，并发现 classpath 中的日期模块，避免
     * 各业务服务自行创建不一致的序列化配置。
     */
    @Bean
    ObjectMapper jackson2ObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    /** 单文件上限与 HTTP multipart 的 30MB 保持一致。 */
    @Bean
    DeterministicDocumentParser deterministicDocumentParser(ObjectMapper objectMapper) {
        return new DeterministicDocumentParser(
                objectMapper, new CanonicalJsonHasher(objectMapper), 30L * 1024L * 1024L);
    }

    /** 运行条件、工具参数和版本内容共用同一规范 JSON 实现。 */
    @Bean
    CanonicalJsonHasher canonicalJsonHasher(ObjectMapper objectMapper) {
        return new CanonicalJsonHasher(objectMapper);
    }
}
