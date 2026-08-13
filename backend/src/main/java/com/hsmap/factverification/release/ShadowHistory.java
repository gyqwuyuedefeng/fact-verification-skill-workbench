package com.hsmap.factverification.release;

import com.hsmap.factverification.run.persistence.VerificationRunRepository;
import java.util.List;
import java.util.Map;

/** 真实影子运行的管理投影；明确声明不存在金标准确率。 */
public record ShadowHistory(
        List<VerificationRunRepository.ShadowRunRow> items, Map<String, Integer> summary, boolean accuracyAvailable) {}
