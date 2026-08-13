package com.hsmap.factverification.document;

import java.util.List;

/** CSV 或电子表格的结构化快照。 */
public record DocumentTable(String name, DocumentLocator locator, List<DocumentRow> rows) {
    public DocumentTable {
        rows = List.copyOf(rows);
    }
}
