package com.hsmap.factverification.evaluation.gate;

import com.hsmap.factverification.evaluation.scoring.CoreMetrics;
import java.util.List;

/** Candidate 与 Stable 的离线门禁输入。 */
public record GateInput(
        int sampleCount,
        CoreMetrics stable,
        CoreMetrics candidate,
        List<String> declaredFailureSampleIds,
        List<String> fixedSampleIds,
        List<String> newHardFailures,
        boolean conditionsLocked) {}
