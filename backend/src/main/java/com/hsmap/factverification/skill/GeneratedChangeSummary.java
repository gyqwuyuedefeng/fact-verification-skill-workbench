package com.hsmap.factverification.skill;

import java.util.List;

/** 公司千问返回的最小版本升级摘要。 */
public record GeneratedChangeSummary(String headline, List<String> changes, List<String> reviewRisks) {}
