package com.hsmap.factverification.claim.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 追加核验主张；结果修正由后续明确的人工复核服务留痕处理。 */
@Repository
public class ClaimRepository {

    private final JdbcTemplate jdbcTemplate;

    public ClaimRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 逐条追加统一输出中的主张，唯一序号防止同一运行重复落库。 */
    public void append(ClaimRow claim) {
        jdbcTemplate.update(
                """
                insert into test.claim
                  (id, run_id, ordinal, claim_text, material_locator, normalized_claim,
                   company_id, company_name, verification_status, risk_flags, evidence_json,
                   explanation, requires_human_intervention, created_at)
                values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                """,
                claim.id(),
                claim.runId(),
                claim.ordinal(),
                claim.claimText(),
                claim.materialLocatorJson(),
                claim.normalizedClaimJson(),
                claim.companyId(),
                claim.companyName(),
                claim.verificationStatus(),
                claim.riskFlagsJson(),
                claim.evidenceJson(),
                claim.explanation(),
                claim.requiresHumanIntervention(),
                claim.createdAt());
    }

    /** 按固定序号读取运行结果，保证页面与导出顺序可复现。 */
    public List<ClaimView> findByRun(UUID runId) {
        return jdbcTemplate.query(
                """
                select id, ordinal, claim_text, material_locator::text, normalized_claim::text,
                       verification_status, evidence_json::text, requires_human_intervention
                       , risk_flags::text, company_id, company_name, explanation
                  from test.claim where run_id = ? order by ordinal
                """,
                (rs, rowNum) -> new ClaimView(
                        rs.getObject("id", UUID.class),
                        rs.getInt("ordinal"),
                        rs.getString("claim_text"),
                        rs.getString("material_locator"),
                        rs.getString("normalized_claim"),
                        rs.getString("verification_status"),
                        rs.getString("risk_flags"),
                        rs.getString("evidence_json"),
                        rs.getString("company_id"),
                        rs.getString("company_name"),
                        rs.getString("explanation"),
                        rs.getBoolean("requires_human_intervention")),
                runId);
    }

    public record ClaimRow(
            UUID id,
            UUID runId,
            int ordinal,
            String claimText,
            String materialLocatorJson,
            String normalizedClaimJson,
            String companyId,
            String companyName,
            String verificationStatus,
            String riskFlagsJson,
            String evidenceJson,
            String explanation,
            boolean requiresHumanIntervention,
            OffsetDateTime createdAt) {}

    public record ClaimView(
            UUID id,
            int ordinal,
            String claimText,
            String materialLocatorJson,
            String normalizedClaimJson,
            String verificationStatus,
            String riskFlagsJson,
            String evidenceJson,
            String companyId,
            String companyName,
            String explanation,
            boolean requiresHumanIntervention) {}
}
