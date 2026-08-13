package com.hsmap.factverification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 企业材料事实核验工作台后端入口。
 *
 * <p>该进程只承载比赛 MVP 的材料解析、Agent 执行、评测、Skill 版本与发布记录，不承担企业证据查询；后者通过独立的只读 MCP
 * Server 暴露，以便对照评测能够锁定工具契约和证据快照。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FactVerificationApplication {

    /** 启动工作台后端。 */
    public static void main(String[] args) {
        SpringApplication.run(FactVerificationApplication.class, args);
    }
}
