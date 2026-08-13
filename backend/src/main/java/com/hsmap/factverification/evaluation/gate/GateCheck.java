package com.hsmap.factverification.evaluation.gate;

/** 一条可展示、可复核的门禁判断。 */
public record GateCheck(String name, boolean passed, String reason) {}
