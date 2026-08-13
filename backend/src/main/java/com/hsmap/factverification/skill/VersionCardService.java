package com.hsmap.factverification.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.evaluation.persistence.EvaluationRunRepository;
import com.hsmap.factverification.shared.ServiceException;
import com.hsmap.factverification.skill.persistence.SkillVersionRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 从冻结 Skill 和关联评测生成版本卡；注册后保存的卡片不可覆盖。 */
@Service
public final class VersionCardService {

    private final SkillVersionRepository versions;
    private final EvaluationRunRepository evaluations;
    private final ObjectMapper objectMapper;

    public VersionCardService(
            SkillVersionRepository versions, EvaluationRunRepository evaluations, ObjectMapper objectMapper) {
        this.versions = versions;
        this.evaluations = evaluations;
        this.objectMapper = objectMapper;
    }

    /** 返回持久化版本卡；未注册 Candidate 返回 PENDING 预览。 */
    public VersionCardView get(UUID versionId) {
        SkillVersionRepository.VersionRow version = findVersion(versionId);
        if (version.versionCardJson() != null) {
            try {
                return objectMapper.readValue(version.versionCardJson(), VersionCardView.class);
            } catch (JsonProcessingException exception) {
                throw new ServiceException("VERSION_CARD_INVALID", "版本卡无法读取");
            }
        }
        return build(version, version.registeredEvaluationId());
    }

    /** 注册事务调用该方法构建最终版本卡。 */
    public VersionCardView build(SkillVersionRepository.VersionRow version, UUID evaluationId) {
        ensureFrozen(version);
        String parentVersion = version.parentVersionId() == null
                ? null
                : versions.findVersion(version.parentVersionId())
                        .map(SkillVersionRepository.VersionRow::version)
                        .orElse(null);
        if (evaluationId == null) {
            return new VersionCardView(
                    SkillVersionService.SKILL_KEY,
                    version.version(),
                    version.status(),
                    parentVersion,
                    version.contentHash(),
                    version.changeSummary(),
                    null,
                    null,
                    "PENDING",
                    List.of());
        }
        EvaluationRunRepository.EvaluationRow evaluation = evaluations
                .find(evaluationId)
                .orElseThrow(() -> new ServiceException("EVALUATION_NOT_FOUND", "版本卡关联评测不存在"));
        return new VersionCardView(
                SkillVersionService.SKILL_KEY,
                version.version(),
                version.status(),
                parentVersion,
                version.contentHash(),
                version.changeSummary(),
                evaluationId,
                readObject(evaluation.metricsJson()),
                evaluation.gateStatus(),
                knownFailures(evaluation.failuresJson()));
    }

    private List<String> knownFailures(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> failures =
                    objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            return failures.stream()
                    .map(failure -> String.valueOf(failure.get("sampleId")))
                    .distinct()
                    .toList();
        } catch (JsonProcessingException exception) {
            throw new ServiceException("EVALUATION_FAILURES_INVALID", "评测失败样本无法读取");
        }
    }

    private Object readObject(String json) {
        try {
            return json == null ? null : objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("EVALUATION_METRICS_INVALID", "评测指标无法读取");
        }
    }

    private SkillVersionRepository.VersionRow findVersion(UUID id) {
        return versions.findVersion(id)
                .orElseThrow(() -> new ServiceException("SKILL_VERSION_NOT_FOUND", "Skill 版本不存在"));
    }

    private static void ensureFrozen(SkillVersionRepository.VersionRow version) {
        if ("DRAFT".equals(version.status()) || version.version() == null || version.contentHash() == null) {
            throw new ServiceException("VERSION_CARD_DRAFT_FORBIDDEN", "DRAFT 不能生成版本卡");
        }
    }
}
