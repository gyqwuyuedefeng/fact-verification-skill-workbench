package com.hsmap.factverification.demo;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * `fact-verification-demo-state/v1` 快照的完整性清单。
 *
 * <p>清单只描述固定七表 JSONL 与三个受管目录中的业务文件，不包含配置、凭据、数据库连接或开发机目录信息。
 */
public record SnapshotManifest(
        String formatVersion, Instant createdAt, Map<String, TableEntry> tables, List<FileEntry> files) {

    public static final String FORMAT_VERSION = "fact-verification-demo-state/v1";

    /** 记录一张固定表的 JSONL 行数和对完整文件字节计算的 SHA-256。 */
    public record TableEntry(long rows, String sha256) {}

    /** 记录一个受管文件在 files 根下的相对路径、字节数与 SHA-256。 */
    public record FileEntry(String path, long size, String sha256) {}
}
