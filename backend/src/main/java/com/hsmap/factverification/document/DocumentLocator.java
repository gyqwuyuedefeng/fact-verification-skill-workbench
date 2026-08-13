package com.hsmap.factverification.document;

import java.util.List;

/** 能从核验结论回到材料原文的统一位置；不同格式只填自身适用字段。 */
public record DocumentLocator(
        String fileId,
        Integer page,
        List<String> sectionPath,
        Integer paragraph,
        Integer tableRow,
        Integer slide,
        Integer textBlock,
        String sheet,
        String cellRange,
        Integer lineStart,
        Integer lineEnd) {

    /** 创建按行定位的 Markdown/TXT/CSV 位置。 */
    public static DocumentLocator lines(String fileId, int start, int end, List<String> sectionPath) {
        return new DocumentLocator(
                fileId, null, List.copyOf(sectionPath), null, null, null, null, null, null, start, end);
    }

    /** 创建 PDF 页位置。 */
    public static DocumentLocator page(String fileId, int page) {
        return new DocumentLocator(fileId, page, List.of(), null, null, null, null, null, null, null, null);
    }

    /** 创建 Word 段落位置。 */
    public static DocumentLocator paragraph(String fileId, int paragraph) {
        return new DocumentLocator(fileId, null, List.of(), paragraph, null, null, null, null, null, null, null);
    }

    /** 创建幻灯片文本块位置。 */
    public static DocumentLocator slide(String fileId, int slide, int textBlock) {
        return new DocumentLocator(fileId, null, List.of(), null, null, slide, textBlock, null, null, null, null);
    }

    /** 创建工作表区域位置。 */
    public static DocumentLocator cells(String fileId, String sheet, String cellRange) {
        return new DocumentLocator(fileId, null, List.of(), null, null, null, null, sheet, cellRange, null, null);
    }
}
