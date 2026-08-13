# 企业证据 MCP 只读集成报告

验证时间：2026-08-13（Asia/Shanghai）

## 结论

六个企业证据工具已在原生 Streamable HTTP `/mcp` 上完成真实 dev 同源 ES 联调。成功、空结果、非法参数三类共 18 个用例全部通过；旧 `/sse` 与 `/mcp/message` 均返回 404。

## 工具与协议

- 协议版本：`2025-03-26`。
- 单一传输端点：`/mcp`。
- 工具：`resolve_company`、`get_company_profile`、`get_company_financials`、`get_company_intellectual_property`、`get_company_risks`、`get_company_relationships`。
- 真实企业覆盖：科大讯飞、金山办公、深信服、浪潮信息、用友网络；解析出的企业 ID 和各工具命中数保存在 `competition-replay/evidence/mcp-live-matrix.json`。

## 十二索引白名单

服务端固定以下索引、`_source` 字段和单次返回上限；模型参数不能成为索引名或字段名：

1. `ads_lget_company_info`
2. `ads_lget_company_revenue`
3. `ads_lget_patent_info`
4. `ads_lget_software_copyright`
5. `ads_lget_product_info`
6. `ads_lget_company_lose_trust`
7. `ads_lget_company_illegal_info`
8. `ads_lget_company_adm_punish`
9. `ads_lget_company_tax_arrears_info`
10. `ads_lget_company_sholder`
11. `ads_lget_company_client`
12. `ads_lget_company_supply`

## 只读与快照边界

- 企业事实只通过固定 ES `_search` 读取；MCP 没有企业业务 PostgreSQL 数据源，也没有写 ES 方法。
- PostgreSQL 只用于比赛 `test` schema 的证据快照重放，独立连接同时设置 Hikari `read-only` 和会话级 `default_transaction_read_only=on`。
- 正式评测的证据快照识别值为 `d8e71bf3d46d7dc24c9e1241580a1770df31ddbf4f5137ed03614bf08916d217`；三个变体对相同规范化请求重放同一内容。
- Maven 全量验证中 `ReadOnlyBoundaryTest`、`EnterpriseEvidenceMcpContractTest`、两个旧 SSE 静态门禁和 `EsEvidenceQueryTest` 全部通过。

机器可读现场结果见 `competition-replay/evidence/mcp-live-matrix.json`。
