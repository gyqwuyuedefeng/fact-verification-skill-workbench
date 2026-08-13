package com.hsmap.factverification.demo;

import java.util.Arrays;
import java.util.Optional;

/**
 * 比赛状态快照唯一允许导入导出的七张表。
 *
 * <p>表名只能由该枚举进入 SQL；HTTP 请求、manifest 或 ZIP entry 即使包含其他名称，也只能用于与枚举比对，不能直接拼接查询。
 */
public enum SnapshotTable {
    VERIFICATION_TASK("verification_task"),
    SKILL_VERSION("skill_version"),
    EVALUATION_RUN("evaluation_run"),
    VERIFICATION_RUN("verification_run"),
    CLAIM("claim"),
    EVIDENCE_SNAPSHOT("evidence_snapshot"),
    RELEASE_BINDING("release_binding");

    private final String tableName;

    /** 绑定迁移中已存在的固定表名；枚举不接受 schema 或调用方自定义后缀。 */
    SnapshotTable(String tableName) {
        this.tableName = tableName;
    }

    /** 返回只用于固定 SQL 和归档路径的白名单表名。 */
    public String tableName() {
        return tableName;
    }

    /** 把 manifest 中的声明映射回枚举；未知名称只返回空，不形成 SQL。 */
    public static Optional<SnapshotTable> fromTableName(String tableName) {
        return Arrays.stream(values())
                .filter(table -> table.tableName.equals(tableName))
                .findFirst();
    }
}
