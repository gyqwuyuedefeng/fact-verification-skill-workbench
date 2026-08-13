package com.hsmap.factverification.mcp.query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 六工具统一返回结构；每条 ES 命中都带索引、记录 ID 和本次观察时间。 */
public record EsEvidenceEnvelope(
        CompanySubject subject,
        OffsetDateTime asOf,
        List<Map<String, Object>> items,
        long total,
        boolean truncated,
        List<EvidenceReference> evidence) {

    public EsEvidenceEnvelope {
        items = List.copyOf(items);
        evidence = List.copyOf(evidence);
    }

    /** companyId 是内部企业编码，不与统一社会信用代码混用。 */
    public record CompanySubject(String companyId, String companyName, String unifiedSocialCreditCode) {}

    /** 原始证据的可追溯标识。 */
    public record EvidenceReference(String source, String dataset, String recordId, OffsetDateTime observedAt) {}
}
