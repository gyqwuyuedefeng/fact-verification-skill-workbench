package com.hsmap.factverification.document;

/** 文档中的一个稳定文本块及其原文位置。 */
public record DocumentBlock(String kind, String text, DocumentLocator locator) {}
