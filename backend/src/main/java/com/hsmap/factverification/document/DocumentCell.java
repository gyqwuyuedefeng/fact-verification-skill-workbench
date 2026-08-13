package com.hsmap.factverification.document;

/** 表格单元格同时保留坐标、原值、显示值与公式状态。 */
public record DocumentCell(
        String coordinate, String rawValue, String displayValue, String formula, boolean calculated) {}
