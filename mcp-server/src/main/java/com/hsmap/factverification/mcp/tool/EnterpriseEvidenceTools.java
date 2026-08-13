package com.hsmap.factverification.mcp.tool;

import com.hsmap.factverification.mcp.config.StreamableTransportConfiguration;
import com.hsmap.factverification.mcp.contract.EvidenceToolExecutor;
import com.hsmap.factverification.mcp.contract.EvidenceToolName;
import com.hsmap.factverification.mcp.shared.ServiceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/** 六个且仅六个只读企业证据工具；所有方法共享快照优先执行器。 */
@Component
public final class EnterpriseEvidenceTools {

    private final EvidenceToolExecutor executor;

    public EnterpriseEvidenceTools(EvidenceToolExecutor executor) {
        this.executor = executor;
    }

    /** 按名称、曾用名、简称或统一社会信用代码返回规范主体候选。 */
    @McpTool(
            name = "resolve_company",
            description = "按企业名称或别名查找规范企业主体候选",
            annotations =
                    @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public Object resolveCompany(
            McpSyncRequestContext context, @McpToolParam(description = "企业名称、别名或统一社会信用代码") String query) {
        return executor.execute(EvidenceToolName.RESOLVE_COMPANY, Map.of("query", query), snapshotId(context));
    }

    /** 查询企业基本工商资料。 */
    @McpTool(
            name = "get_company_profile",
            description = "按 companyId 查询企业基本资料",
            annotations =
                    @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public Object getCompanyProfile(
            McpSyncRequestContext context, @McpToolParam(description = "企业内部 companyId") String companyId) {
        return executeCompanyTool(context, EvidenceToolName.GET_COMPANY_PROFILE, companyId);
    }

    /** 查询固定报告期上限内的企业财务事实。 */
    @McpTool(
            name = "get_company_financials",
            description = "按 companyId 查询企业财务指标",
            annotations =
                    @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public Object getCompanyFinancials(
            McpSyncRequestContext context, @McpToolParam(description = "企业内部 companyId") String companyId) {
        return executeCompanyTool(context, EvidenceToolName.GET_COMPANY_FINANCIALS, companyId);
    }

    /** 聚合专利、软件著作权和产品证据。 */
    @McpTool(
            name = "get_company_intellectual_property",
            description = "按 companyId 查询专利、软件著作权和产品证据",
            annotations =
                    @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public Object getCompanyIntellectualProperty(
            McpSyncRequestContext context, @McpToolParam(description = "企业内部 companyId") String companyId) {
        return executeCompanyTool(context, EvidenceToolName.GET_COMPANY_INTELLECTUAL_PROPERTY, companyId);
    }

    /** 聚合失信、违法、行政处罚和欠税证据。 */
    @McpTool(
            name = "get_company_risks",
            description = "按 companyId 查询企业风险证据",
            annotations =
                    @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public Object getCompanyRisks(
            McpSyncRequestContext context, @McpToolParam(description = "企业内部 companyId") String companyId) {
        return executeCompanyTool(context, EvidenceToolName.GET_COMPANY_RISKS, companyId);
    }

    /** 聚合股东、客户和供应商关系证据。 */
    @McpTool(
            name = "get_company_relationships",
            description = "按 companyId 查询股东、客户和供应商关系证据",
            annotations =
                    @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public Object getCompanyRelationships(
            McpSyncRequestContext context, @McpToolParam(description = "企业内部 companyId") String companyId) {
        return executeCompanyTool(context, EvidenceToolName.GET_COMPANY_RELATIONSHIPS, companyId);
    }

    private Object executeCompanyTool(McpSyncRequestContext context, EvidenceToolName toolName, String companyId) {
        return executor.execute(toolName, Map.of("companyId", companyId), snapshotId(context));
    }

    /** 快照 ID 必须来自本次 Streamable HTTP transport context，不能使用全局可变状态。 */
    private static UUID snapshotId(McpSyncRequestContext context) {
        Object raw = context.transportContext().get(StreamableTransportConfiguration.SNAPSHOT_CONTEXT_KEY);
        if (raw == null) {
            throw new ServiceException("EVIDENCE_SNAPSHOT_REQUIRED", "缺少证据快照请求头");
        }
        try {
            return UUID.fromString(String.valueOf(raw));
        } catch (IllegalArgumentException exception) {
            throw new ServiceException("EVIDENCE_SNAPSHOT_INVALID", "证据快照标识格式无效");
        }
    }
}
