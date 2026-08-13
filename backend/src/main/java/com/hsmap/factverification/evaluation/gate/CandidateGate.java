package com.hsmap.factverification.evaluation.gate;

import java.util.ArrayList;
import java.util.List;

/**
 * 赛题 Candidate 离线门禁。
 *
 * <p>直接比较分子而非浮点百分比，避免分母相同情况下的舍入争议。
 */
public final class CandidateGate {

    /** 执行完整锁定、四项不退化、修复声明样本和无新增硬错误检查。 */
    public GateResult evaluate(GateInput input) {
        List<GateCheck> checks = new ArrayList<>();
        checks.add(check("sample-count", input.sampleCount() >= 30, "门禁数据集不少于 30 条"));
        checks.add(check("conditions-locked", input.conditionsLocked(), "同条件清单完整锁定"));
        checks.add(check(
                "accuracy-non-regression",
                input.candidate().accuracy().numerator()
                        >= input.stable().accuracy().numerator(),
                "Candidate 正确主张数不得低于 Stable"));
        checks.add(check(
                "completion-non-regression",
                input.candidate().completionRate().numerator()
                        >= input.stable().completionRate().numerator(),
                "Candidate 完成任务数不得低于 Stable"));
        checks.add(check(
                "stability-non-regression",
                input.candidate().stability().numerator()
                        >= input.stable().stability().numerator(),
                "Candidate 三次一致样本数不得低于 Stable"));
        checks.add(check(
                "intervention-non-regression",
                input.candidate().humanInterventionRate().numerator()
                        <= input.stable().humanInterventionRate().numerator(),
                "Candidate 人工介入样本数不得高于 Stable"));
        List<String> declared = safe(input.declaredFailureSampleIds());
        List<String> fixed = safe(input.fixedSampleIds());
        checks.add(check(
                "declared-failure-fixed",
                !fixed.isEmpty() && (declared.isEmpty() || declared.stream().anyMatch(fixed::contains)),
                "至少修复一条变更说明中声明的 Stable 失败样本"));
        checks.add(check("no-new-hard-failure", safe(input.newHardFailures()).isEmpty(), "不得新增主体误配或无证据断言"));
        boolean passed = checks.stream().allMatch(GateCheck::passed);
        return new GateResult(passed ? "PASS" : "FAIL", List.copyOf(checks));
    }

    private static GateCheck check(String name, boolean passed, String reason) {
        return new GateCheck(name, passed, reason);
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }
}
