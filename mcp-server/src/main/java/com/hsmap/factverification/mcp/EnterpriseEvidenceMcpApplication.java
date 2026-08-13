package com.hsmap.factverification.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 企业证据 MCP Server 入口。
 *
 * <p>该进程只开放六个只读企业证据工具，并使用原生 Streamable HTTP 单端点；禁止复用现有系统的旧 SSE 双端点和用户令牌链路。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EnterpriseEvidenceMcpApplication {

    /** 启动只读 MCP Server。 */
    public static void main(String[] args) {
        SpringApplication.run(EnterpriseEvidenceMcpApplication.class, args);
    }
}
