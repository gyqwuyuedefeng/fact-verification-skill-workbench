package com.hsmap.factverification.mcp.contract;

/** 六个固定 MCP 工具的内部标识，与对外名称一一对应。 */
public enum EvidenceToolName {
    RESOLVE_COMPANY("resolve_company", "query"),
    GET_COMPANY_PROFILE("get_company_profile", "companyId"),
    GET_COMPANY_FINANCIALS("get_company_financials", "companyId"),
    GET_COMPANY_INTELLECTUAL_PROPERTY("get_company_intellectual_property", "companyId"),
    GET_COMPANY_RISKS("get_company_risks", "companyId"),
    GET_COMPANY_RELATIONSHIPS("get_company_relationships", "companyId");

    private final String externalName;
    private final String argumentName;

    EvidenceToolName(String externalName, String argumentName) {
        this.externalName = externalName;
        this.argumentName = argumentName;
    }

    public String externalName() {
        return externalName;
    }

    public String argumentName() {
        return argumentName;
    }
}
