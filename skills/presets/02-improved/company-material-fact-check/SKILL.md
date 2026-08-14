---
name: company-material-fact-check
description: Verify explicit factual claims in authorized company materials against six read-only enterprise evidence tools. Use when processing a deterministic document snapshot that needs traceable claim extraction, company resolution, normalized comparisons, and VERIFIED/CONFLICT/INSUFFICIENT conclusions.
---

# 企业材料事实核验

只核验材料中明确出现、能定位原文的企业事实。不得补写材料未声称的事实，不得使用模型记忆代替工具证据。

## 执行流程

1. 直接从文档快照提取一条可核验主张，逐字保留输入已有的 `materialLocator`；不要为单条材料补写其他主张。
2. 对每条主张独立调用 `resolve_company`。若多个高置信候选不能唯一判定，停止该主张的后续取证并请求人工确认。
3. 按事实类型只调用以下相关工具：
   - 工商资料：`get_company_profile`
   - 财务指标：`get_company_financials`
   - 专利、软著、产品：`get_company_intellectual_property`
   - 失信、违法、处罚、欠税：`get_company_risks`
   - 股东、客户、供应商：`get_company_relationships`
4. 直接按主体、指标、期间、口径、数值、单位的顺序比较；不要额外读取 reference 文件，减少模型往返。
5. 严格输出调用方提供的 JSON schema，不输出 Markdown 解释或 schema 外字段。

## 硬约束

- `VERIFIED` 必须同时有有效 `materialLocator` 和至少一条带 `dataset`、`recordId`、`observedAt` 的外部证据。
- 主体不唯一、工具失败、无外部记录、期间不一致或口径无法对齐时输出 `INSUFFICIENT`。
- 同主体、同指标、同期间和同口径的证据明确不一致时输出 `CONFLICT`。
- 风险是 `riskFlags`，不能替代三种核验状态。
- 不把 `companyId` 当作统一社会信用代码；两者必须按证据字段分别保存。
- 不调用六工具之外的外部能力，不修改任何企业业务数据。

## 单主张快速路径

- “截至本次证据检索”的工商和存在性事实使用 `CURRENT`；明确年份必须逐字保留。
- 先从 `resolve_company` 取唯一 `company_code`，再把它作为后续工具的 `companyId`；简称歧义不得猜主体。
- 财务值先统一元、万元、亿元再比较；证据不一致输出 `CONFLICT`。
- `VERIFIED`/`CONFLICT` 的 evidence 引用与 items 必须用相同 `recordId` 配对，并原样复制对应 item 为 content。
- 否定性风险、缺期间、空结果或工具失败一律 `INSUFFICIENT` 且请求人工介入。

## 归一化与主体消歧

- `normalizedClaim` 只表达材料原文，不得用证据值重写。“存在至少一条”固定为 `operator=EXISTS`、`value=true`、`unit=null`；金额保留材料原数值和原单位。
- 风险否定主张固定为 `metric=riskRecordAbsence`、`operator=EQUALS`、`value=true`；空结果仍是 `INSUFFICIENT`。
- 全称查询返回多条同 `company_name` 且同 `uni_code` 的记录时，它们是主索引重复实体，不是业务上的同名公司。最多对前两个不同 `company_code` 调用本主张对应证据工具，选择 `total` 较大者；并列时保留搜索顺序第一条。
- 简称只在返回候选中恰好一条 `company_sname` 与材料简称完全相同时可唯一定位；不得因公司全称仅包含该短词就选第一条。
- 工商主张不需要为选主体扫描其他索引；完整企业全称的重复记录并列时取第一条。

## 最终输出前自检

- 每条输入只保留一个对应主张，`claimText`、locator 和归一化五元组不得改写。
- `subject.companyId` 必须来自主体工具，不能误填股票代码或统一社会信用代码。
- `VERIFIED`/`CONFLICT` 必须有与 items 同 recordId 的 evidence；否则降级为 `INSUFFICIENT`。
- 输出前检查顶层运行元数据逐字复制、JSON 无代码围栏且只含 schema 字段。

## 空结果判定补强

- 对“存在至少一条”的正向存在性主张，对应证据工具返回 `total=0` 只表示当前索引未返回直接支持，不构成反证；必须输出 `INSUFFICIENT`、`evidence=[]` 并请求人工介入，不能输出 `CONFLICT`。

## 地址文本归一化补强

- 中文注册地址比较时，`claimText` 仍逐字保留材料原文；`normalizedClaim.value` 删除字符之间无语义的 Unicode 空白后再与证据比较。例如门牌号中用于排版的空格不属于地址值，不得因保留该空格造成规范化字段失配。
