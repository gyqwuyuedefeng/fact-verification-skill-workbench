package com.hsmap.factverification.document;

import java.util.List;

/** 一份材料在固定解析器下的不可变结果和两个内容识别值。 */
public record DocumentSnapshot(
        String fileId,
        String parserVersion,
        String fileHash,
        String snapshotHash,
        List<DocumentBlock> blocks,
        List<DocumentTable> tables,
        List<String> warnings) {
    public DocumentSnapshot {
        blocks = List.copyOf(blocks);
        tables = List.copyOf(tables);
        warnings = List.copyOf(warnings);
    }
}
