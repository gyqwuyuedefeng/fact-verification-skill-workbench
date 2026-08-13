package com.hsmap.factverification.demo;

/**
 * test-only 遗留核验恢复结果。
 *
 * <p>只公开任务、运行恢复数量和既有脱敏状态，不暴露恢复对象 UUID、数据库时间或真实存储路径。
 */
public record StaleRecoveryView(int recoveredTasks, int recoveredRuns, DemoStateView status) {}
