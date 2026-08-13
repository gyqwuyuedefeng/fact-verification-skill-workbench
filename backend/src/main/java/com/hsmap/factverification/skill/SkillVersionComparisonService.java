package com.hsmap.factverification.skill;

import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 冻结 Skill 的按需比较：确定性差异是事实，模型摘要只是可失败的辅助解释。 */
@Service
public class SkillVersionComparisonService {

    private static final String ADVISORY = "模型生成、仅供审核参考";
    private static final int MAX_SUMMARY_CONTENT = 30_000;

    private final SkillVersionRepository versions;
    private final SkillChangeSummaryClient summaryClient;

    public SkillVersionComparisonService(SkillVersionRepository versions, SkillChangeSummaryClient summaryClient) {
        this.versions = versions;
        this.summaryClient = summaryClient;
    }

    /** 两个版本都必须已冻结；DRAFT 的可变内容不能进入版本比较。 */
    public VersionComparison compare(UUID targetVersionId, UUID baseVersionId) {
        SkillVersionRepository.VersionRow target = frozen(targetVersionId);
        SkillVersionRepository.VersionRow base = frozen(baseVersionId);
        String targetContent = content(target);
        String baseContent = content(base);
        String diff = lineDiff(baseContent, targetContent);
        try {
            GeneratedChangeSummary summary = summaryClient.summarize(truncate(baseContent), truncate(targetContent));
            return new VersionComparison(targetVersionId, baseVersionId, diff, "COMPLETED", summary, ADVISORY, null);
        } catch (RuntimeException exception) {
            return new VersionComparison(
                    targetVersionId, baseVersionId, diff, "UNAVAILABLE", null, ADVISORY, "MODEL_SUMMARY_UNAVAILABLE");
        }
    }

    private SkillVersionRepository.VersionRow frozen(UUID id) {
        SkillVersionRepository.VersionRow value = versions.findVersion(id)
                .orElseThrow(() -> new ServiceException("SKILL_VERSION_NOT_FOUND", "Skill 版本不存在"));
        if ("DRAFT".equals(value.status())) {
            throw new ServiceException("SKILL_VERSION_NOT_FROZEN", "只有冻结版本可以生成升级说明");
        }
        return value;
    }

    private static String content(SkillVersionRepository.VersionRow version) {
        return version.skillMarkdown() + "\n\n# references\n" + version.referencesJson();
    }

    private static String truncate(String value) {
        return value.length() <= MAX_SUMMARY_CONTENT ? value : value.substring(0, MAX_SUMMARY_CONTENT);
    }

    /** 使用 LCS 生成稳定逐行差异，不依赖 git 命令或可变外部工具。 */
    static String lineDiff(String base, String target) {
        String[] left = base.split("\\R", -1);
        String[] right = target.split("\\R", -1);
        int[][] lcs = new int[left.length + 1][right.length + 1];
        for (int i = left.length - 1; i >= 0; i--) {
            for (int j = right.length - 1; j >= 0; j--) {
                lcs[i][j] = left[i].equals(right[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> lines = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < left.length || j < right.length) {
            if (i < left.length && j < right.length && left[i].equals(right[j])) {
                lines.add(" " + left[i]);
                i++;
                j++;
            } else if (j < right.length && (i == left.length || lcs[i][j + 1] >= lcs[i + 1][j])) {
                lines.add("+" + right[j++]);
            } else {
                lines.add("-" + left[i++]);
            }
        }
        return String.join("\n", lines);
    }
}
