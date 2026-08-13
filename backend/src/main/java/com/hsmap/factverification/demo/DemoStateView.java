package com.hsmap.factverification.demo;

import java.util.Map;

/**
 * 比赛演示当前状态的只读投影。
 *
 * <p>表名和目录名均由服务端固定，页面只能据此确认七张业务表是否已清空及三个运行目录是否仍有运行产物。
 */
public record DemoStateView(Map<String, Long> tableCounts, Map<String, Boolean> storageEmpty) {}
