package com.hsmap.factverification.task;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 与 OpenAPI VerificationTask schema 对齐的页面投影。 */
public record VerificationTaskView(
        UUID id,
        String inputType,
        boolean messagePresent,
        String executionMode,
        String fileName,
        String fileHash,
        String documentSnapshotHash,
        String status,
        UUID primaryRunId,
        String errorCode,
        OffsetDateTime createdAt) {

    /** 兼容旧 API 测试投影；影子字段不再进入普通页面返回值。 */
    public VerificationTaskView(
            UUID id,
            String fileName,
            String fileHash,
            String documentSnapshotHash,
            String status,
            boolean shadowRequested,
            UUID primaryRunId,
            UUID shadowRunId,
            String errorCode,
            OffsetDateTime createdAt) {
        this(
                id,
                fileName == null ? null : "FILE",
                false,
                primaryRunId == null ? null : "STABLE",
                fileName,
                fileHash,
                documentSnapshotHash,
                status,
                primaryRunId,
                errorCode,
                createdAt);
    }
}
