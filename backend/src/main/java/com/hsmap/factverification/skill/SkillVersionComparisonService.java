package com.hsmap.factverification.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.util.ArrayList;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 管理冻结 Skill 版本之间可恢复升级说明的应用服务。
 *
 * <p>服务先根据两份冻结正文计算稳定的逐行差异，再按需调用模型补充审核摘要。成功摘要以“目标版本 + 基础版本”作为唯一维度写入目标版本的 JSONB 字段，页面刷新仅恢复快照而不会重复调用模型。模型不可用时，服务优先保留旧成功快照；没有快照时返回含确定性差异的 {@code UNAVAILABLE} 结果，绝不传播模型原始异常。
 *
 * <p>版本比较只是管理员审核辅助：不修改 Skill 冻结内容、版本生命周期、版本卡或发布门禁。持久化、读取和解析异常均转换为稳定的 {@link ServiceException}，防止数据库 JSON、模型响应或底层异常泄露至 API。
 */
@Service
public class SkillVersionComparisonService {

    private static final String ADVISORY = "模型生成、仅供审核参考";
    private static final String UNKNOWN_MODEL_ID = "unknown";
    private static final int MAX_SUMMARY_CONTENT = 30_000;

    private final SkillVersionRepository versions;
    private final SkillChangeSummaryClient summaryClient;
    private final ObjectMapper objectMapper;

    /**
     * 注入冻结版本仓储、模型摘要客户端和 JSON 序列化器。
     *
     * <p>仓储负责限定已冻结版本的比较快照读写，客户端只负责可能失败的外部模型调用，序列化器负责把完整 DTO 固化为可恢复 JSON；三者分离保证模型故障不会改变版本或门禁状态。
     *
     * @param versions 冻结版本与比较快照的持久化访问入口
     * @param summaryClient 生成审核辅助摘要的外部模型客户端
     * @param objectMapper 序列化和恢复完整比较 DTO 的 JSON 工具
     */
    public SkillVersionComparisonService(
            SkillVersionRepository versions, SkillChangeSummaryClient summaryClient, ObjectMapper objectMapper) {
        this.versions = versions;
        this.summaryClient = summaryClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 调用模型生成并保存两个冻结版本的升级说明。
     *
     * <p>成功结果先构造为可恢复的完整 DTO 再写入目标版本。模型失败时绝不覆盖旧成功结果：若已有快照则返回该快照并标记本次模型不可用，否则只返回确定性差异和 UNAVAILABLE 状态。
     *
     * @param targetVersionId 需要审核的目标冻结版本
     * @param baseVersionId 用作比较基线的冻结版本
     * @return 已保存的成功说明，或带稳定降级错误码的可审核结果
     */
    public VersionComparison generate(UUID targetVersionId, UUID baseVersionId) {
        ComparisonContext context = context(targetVersionId, baseVersionId);
        GeneratedChangeSummary summary;
        String modelId;
        try {
            summary = summaryClient.summarize(truncate(context.baseContent()), truncate(context.targetContent()));
            modelId = summaryClient.modelId();
        } catch (RuntimeException exception) {
            return previousOrUnavailable(targetVersionId, baseVersionId, context.diff());
        }
        VersionComparison generated = new VersionComparison(
                targetVersionId,
                baseVersionId,
                context.diff(),
                "COMPLETED",
                summary,
                ADVISORY,
                null,
                modelId,
                OffsetDateTime.now(ZoneOffset.UTC),
                true);
        save(targetVersionId, baseVersionId, generated);
        return generated;
    }

    /**
     * 恢复已生成的比较说明，绝不触发模型调用。
     *
     * <p>页面刷新只需读取目标版本的持久化快照；尚未生成时保留确定性差异并返回 NOT_GENERATED，令前端可以明确展示“尚未生成”而非误判模型故障。
     *
     * @param targetVersionId 需要查看的目标冻结版本
     * @param baseVersionId 用作比较基线的冻结版本
     * @return 已保存说明或不调用模型的未生成结果
     */
    public VersionComparison get(UUID targetVersionId, UUID baseVersionId) {
        ComparisonContext context = context(targetVersionId, baseVersionId);
        Optional<String> saved = findSaved(targetVersionId, baseVersionId);
        return saved.map(this::parse).orElseGet(() -> new VersionComparison(
                targetVersionId,
                baseVersionId,
                context.diff(),
                "NOT_GENERATED",
                null,
                ADVISORY,
                null,
                null,
                null,
                false));
    }

    /**
     * 读取并校验两个冻结版本，然后一次性计算稳定逐行差异。
     *
     * <p>无论读取、首次生成还是降级恢复，都以同一份冻结正文作为差异事实，确保模型是否可用不会影响比较底座。
     */
    private ComparisonContext context(UUID targetVersionId, UUID baseVersionId) {
        SkillVersionRepository.VersionRow target = frozen(targetVersionId);
        SkillVersionRepository.VersionRow base = frozen(baseVersionId);
        String targetContent = content(target);
        String baseContent = content(base);
        return new ComparisonContext(baseContent, targetContent, lineDiff(baseContent, targetContent));
    }

    /**
     * 将完整 DTO 序列化为 JSON 并写入仓储。
     *
     * <p>序列化失败、数据库调用失败或没有更新到目标冻结版本均转为脱敏的 {@link ServiceException}，不能把原 JSON 或底层异常带到 API 响应。
     */
    private void save(UUID targetVersionId, UUID baseVersionId, VersionComparison comparison) {
        try {
            String json = objectMapper.writeValueAsString(comparison);
            if (versions.saveComparisonSummary(targetVersionId, baseVersionId, json) != 1) {
                throw new ServiceException("SKILL_VERSION_COMPARISON_SAVE_FAILED", "升级说明保存失败");
            }
        } catch (JsonProcessingException exception) {
            throw new ServiceException("SKILL_VERSION_COMPARISON_SERIALIZE_FAILED", "升级说明保存失败");
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ServiceException("SKILL_VERSION_COMPARISON_SAVE_FAILED", "升级说明保存失败");
        }
    }

    /**
     * 在模型调用失败后优先保留已保存的成功说明。
     *
     * <p>旧说明代表已完成的历史生成，故维持其完成状态和生成时间，但为本次请求附加稳定的模型不可用错误码；从未生成时才返回 UNAVAILABLE。
     */
    private VersionComparison previousOrUnavailable(UUID targetVersionId, UUID baseVersionId, String diff) {
        Optional<String> saved = findSaved(targetVersionId, baseVersionId);
        if (saved.isPresent()) {
            VersionComparison previous = parse(saved.get());
            return new VersionComparison(
                    previous.targetVersionId(),
                    previous.baseVersionId(),
                    previous.deterministicDiff(),
                    previous.summaryStatus(),
                    previous.generatedSummary(),
                    previous.advisory(),
                    "MODEL_SUMMARY_UNAVAILABLE",
                    previous.modelId(),
                    previous.generatedAt(),
                    previous.persisted());
        }
        return new VersionComparison(
                targetVersionId,
                baseVersionId,
                diff,
                "UNAVAILABLE",
                null,
                ADVISORY,
                "MODEL_SUMMARY_UNAVAILABLE",
                UNKNOWN_MODEL_ID,
                null,
                false);
    }

    /**
     * 读取目标版本下指定基础版本键的持久化 JSON。
     *
     * <p>仓储层异常统一转换为稳定读取错误，防止 JDBC 信息或 JSON 内容泄露给审核页面。
     */
    private Optional<String> findSaved(UUID targetVersionId, UUID baseVersionId) {
        try {
            return versions.findComparisonSummary(targetVersionId, baseVersionId);
        } catch (RuntimeException exception) {
            throw new ServiceException("SKILL_VERSION_COMPARISON_READ_FAILED", "升级说明读取失败");
        }
    }

    /**
     * 将数据库中的历史快照恢复为完整 DTO。
     *
     * <p>只接受服务端曾保存的结构；任何损坏或不兼容内容均用稳定业务错误中止读取，避免把原始 JSON 返回到 HTTP 层。
     */
    private VersionComparison parse(String json) {
        try {
            return objectMapper.readValue(json, VersionComparison.class);
        } catch (Exception exception) {
            throw new ServiceException("SKILL_VERSION_COMPARISON_PARSE_FAILED", "已保存的升级说明格式无效");
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

    /** 生成与读取流程共享的冻结正文和确定性差异，避免同一请求重复读取版本。 */
    private record ComparisonContext(String baseContent, String targetContent, String diff) {}
}
