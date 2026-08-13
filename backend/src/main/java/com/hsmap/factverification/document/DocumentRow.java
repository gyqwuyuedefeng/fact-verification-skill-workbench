package com.hsmap.factverification.document;

import java.util.List;

/** 结构化表格的一行，行号使用材料中的一基编号。 */
public record DocumentRow(int rowNumber, List<DocumentCell> cells) {
    public DocumentRow {
        cells = List.copyOf(cells);
    }
}
