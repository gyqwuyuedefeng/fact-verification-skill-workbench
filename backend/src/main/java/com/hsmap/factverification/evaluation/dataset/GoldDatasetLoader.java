package com.hsmap.factverification.evaluation.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsmap.factverification.shared.CanonicalJsonHasher;
import com.hsmap.factverification.shared.ServiceException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 manifest + JSONL 装载比赛金标集。
 *
 * <p>清单是唯一顺序来源；装载时一次性验证数量、重复标识和评分必填字段，使不完整数据不能进入评测。
 */
public final class GoldDatasetLoader {

    public static final String FORMAL_DATASET_VERSION = "public-tech-2024-v3";
    public static final String LIVE_SMOKE_DATASET_VERSION = "public-tech-live-smoke-v1";
    public static final int MIN_GATE_SAMPLE_COUNT = 30;
    public static final int LIVE_SMOKE_SAMPLE_COUNT = 3;
    private static final Map<String, String> EVIDENCE_TOOL_ARGUMENTS = Map.of(
            "resolve_company", "query",
            "get_company_profile", "companyId",
            "get_company_financials", "companyId",
            "get_company_intellectual_property", "companyId",
            "get_company_risks", "companyId",
            "get_company_relationships", "companyId");

    private final ObjectMapper objectMapper;
    private final CanonicalJsonHasher hasher;

    public GoldDatasetLoader(ObjectMapper objectMapper, CanonicalJsonHasher hasher) {
        this.objectMapper = objectMapper;
        this.hasher = hasher;
    }

    /** 装载并返回不可变、顺序固定的金标数据集。 */
    public GoldDataset load(Path manifestPath) {
        return load(manifestPath, MIN_GATE_SAMPLE_COUNT);
    }

    /**
     * 按调用方已锁定的最小样本数装载数据集。
     *
     * <p>正式门禁继续走单参数方法并固定要求三十条；只有现场快速评测会显式传入三，不能通过清单内容自行降低门槛。
     */
    public GoldDataset load(Path manifestPath, int minimumSampleCount) {
        if (minimumSampleCount < 1) {
            throw new ServiceException("DATASET_MINIMUM_INVALID", "数据集最小样本数必须为正数");
        }
        try {
            Path normalizedManifest = manifestPath.toAbsolutePath().normalize();
            DatasetManifest manifest = objectMapper.readValue(normalizedManifest.toFile(), DatasetManifest.class);
            validateManifest(manifest, minimumSampleCount);
            Path baseDirectory = normalizedManifest.getParent();
            Path datasetPath = baseDirectory.resolve(manifest.datasetFile()).normalize();
            if (!datasetPath.startsWith(baseDirectory)) {
                throw new ServiceException("DATASET_PATH_INVALID", "数据集文件必须位于清单目录内");
            }

            Map<String, GoldSample> samplesById = new LinkedHashMap<>();
            for (String line : Files.readAllLines(datasetPath)) {
                if (line.isBlank()) {
                    continue;
                }
                GoldSample sample = objectMapper.readValue(line, GoldSample.class);
                validateSample(sample);
                if (samplesById.putIfAbsent(sample.sampleId(), sample) != null) {
                    throw new ServiceException("DATASET_SAMPLE_DUPLICATE", "金标样本标识重复");
                }
            }
            if (samplesById.size() != manifest.sampleCount()
                    || samplesById.size() != manifest.sampleIds().size()) {
                throw new ServiceException("DATASET_COUNT_MISMATCH", "金标样本数量与清单不一致");
            }
            List<GoldSample> ordered = manifest.sampleIds().stream()
                    .map(id -> {
                        GoldSample sample = samplesById.get(id);
                        if (sample == null) {
                            throw new ServiceException("DATASET_SAMPLE_MISSING", "清单引用了不存在的金标样本");
                        }
                        return sample;
                    })
                    .toList();
            if (samplesById.keySet().stream()
                    .anyMatch(id -> !manifest.sampleIds().contains(id))) {
                throw new ServiceException("DATASET_SAMPLE_UNDECLARED", "数据文件包含清单外样本");
            }
            String contentHash = hasher.hash(Map.of(
                    "version", manifest.version(),
                    "license", manifest.license(),
                    "samples", ordered));
            return new GoldDataset(manifest.version(), manifest.license(), contentHash, List.copyOf(ordered));
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ServiceException("DATASET_INVALID", "金标数据集无法读取或格式不合法");
        }
    }

    private static void validateManifest(DatasetManifest manifest, int minimumSampleCount) {
        if (manifest == null
                || blank(manifest.version())
                || blank(manifest.datasetFile())
                || blank(manifest.license())
                || manifest.sampleIds() == null) {
            throw new ServiceException("DATASET_MANIFEST_INVALID", "数据集清单缺少必填字段");
        }
        if (manifest.sampleCount() < minimumSampleCount) {
            throw new ServiceException("DATASET_TOO_SMALL", "评测数据集至少 " + minimumSampleCount + " 条金标主张");
        }
        if (manifest.sampleIds().stream().anyMatch(GoldDatasetLoader::blank)
                || manifest.sampleIds().stream().distinct().count()
                        != manifest.sampleIds().size()) {
            throw new ServiceException("DATASET_ORDER_INVALID", "清单样本顺序包含空值或重复标识");
        }
    }

    private static void validateSample(GoldSample sample) {
        if (sample == null
                || blank(sample.sampleId())
                || blank(sample.category())
                || missing(sample.material())
                || missing(sample.expectedSubject())
                || missing(sample.normalizedClaim())
                || blank(sample.expectedStatus())
                || missing(sample.acceptableCriteria())) {
            throw new ServiceException("DATASET_SAMPLE_INVALID", "金标样本缺少评分必填字段");
        }
        if (!List.of("VERIFIED", "CONFLICT", "INSUFFICIENT").contains(sample.expectedStatus())) {
            throw new ServiceException("DATASET_STATUS_INVALID", "金标样本结论不在允许范围");
        }
        if (sample.manualEvidence() == null || sample.manualEvidence().isEmpty()) {
            throw new ServiceException("DATASET_EVIDENCE_MISSING", "金标样本必须包含人工证据");
        }
        if (sample.evidenceRequests() == null || sample.evidenceRequests().isEmpty()) {
            throw new ServiceException("DATASET_REQUEST_MISSING", "金标样本必须声明评测前冻结的证据请求");
        }
        boolean invalidRequest = sample.evidenceRequests().stream().anyMatch(request -> {
            if (request == null || blank(request.toolName()) || request.arguments() == null) {
                return true;
            }
            String argumentName = EVIDENCE_TOOL_ARGUMENTS.get(request.toolName());
            return argumentName == null
                    || !request.arguments().isObject()
                    || request.arguments().size() != 1
                    || request.arguments().path(argumentName).asText("").isBlank();
        });
        if (invalidRequest) {
            throw new ServiceException("DATASET_REQUEST_INVALID", "金标证据请求不符合六工具固定参数契约");
        }
        boolean invalidEvidence = sample.manualEvidence().stream()
                .anyMatch(evidence -> evidence == null
                        || blank(evidence.toolName())
                        || blank(evidence.dataset())
                        || blank(evidence.recordId())
                        || !validInstant(evidence.retrievedAt())
                        || blank(evidence.sourceUrl())
                        || blank(evidence.sourceLocator()));
        if (invalidEvidence) {
            throw new ServiceException("DATASET_EVIDENCE_INVALID", "金标样本人工证据字段不完整");
        }
        boolean evidenceWithoutRequest = sample.manualEvidence().stream()
                .anyMatch(evidence -> sample.evidenceRequests().stream()
                        .noneMatch(request -> request.toolName().equals(evidence.toolName())));
        if (evidenceWithoutRequest) {
            throw new ServiceException("DATASET_REQUEST_INCOMPLETE", "人工证据引用的工具必须出现在冻结请求中");
        }
    }

    private static boolean missing(com.fasterxml.jackson.databind.JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode() || value.isEmpty();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean validInstant(String value) {
        if (blank(value)) {
            return false;
        }
        try {
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }
}
