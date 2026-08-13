package com.hsmap.factverification.evaluation.scoring;

import java.util.List;

/** 同一样本在锁定条件下连续三次执行的“主张标识:核验结论”序列。 */
public record StabilityObservation(String sampleId, List<String> subjectStatusRuns) {

    /**
     * 稳定性口径要求恰好三次且三次结果完全相同。
     *
     * <p>少跑一次也不能算稳定，从而避免执行失败被误记为一致。
     */
    public boolean consistent() {
        return subjectStatusRuns != null
                && subjectStatusRuns.size() == 3
                && subjectStatusRuns.stream().distinct().count() == 1;
    }
}
