package com.hsmap.factverification.evaluation.gate;

import java.util.List;

/** Candidate 门禁汇总；任一硬检查失败即 FAIL。 */
public record GateResult(String status, List<GateCheck> checks) {}
