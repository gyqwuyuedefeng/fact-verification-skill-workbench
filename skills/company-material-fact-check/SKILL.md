---
name: company-material-fact-check
description: 核验授权企业材料中的明确事实主张。用于需要可定位主张抽取、企业主体消歧、六类只读证据查询、口径归一化和 VERIFIED/CONFLICT/INSUFFICIENT 可追溯结论的文本或文件快照。
slug: company-material-fact-check
model: company-configured-qwen
aliases:
  - 企业材料事实核验
  - 企业事实核验
when_to_use: 当用户输入企业材料文本或上传可解析文件，并要求核验其中的工商、财务、知识产权、风险或关系事实时使用
argument_hint: '[企业材料文本或文件快照]，如：核验材料中的企业名称、2024 年营业收入和专利数量'
tools:
  - resolve_company
  - get_company_profile
  - get_company_financials
  - get_company_intellectual_property
  - get_company_risks
  - get_company_relationships
params:
  - key: material
    label: 企业材料内容
    type: text
    required: true
---

# 企业材料事实核验

只核验材料中明确出现、能定位原文的企业事实。不得补写材料未声称的事实，不得使用模型记忆代替工具证据。

## 执行流程

1. 应用 `references/claim-normalization.md`，从文档快照提取可核验主张并原样保留 `materialLocator`。
2. 对每条主张独立调用 `resolve_company`。若多个高置信候选不能唯一判定，停止该主张的后续取证并请求人工确认。
3. 按事实类型只调用以下相关工具：
   - 工商资料：`get_company_profile`
   - 财务指标：`get_company_financials`
   - 专利、软著、产品：`get_company_intellectual_property`
   - 失信、违法、处罚、欠税：`get_company_risks`
   - 股东、客户、供应商：`get_company_relationships`
4. 应用 `references/evidence-rules.md`，对齐主体、指标、期间、口径、数值和单位后再判定。
5. 严格输出调用方提供的 JSON schema，不输出 Markdown 解释或 schema 外字段。

## 硬约束

- `VERIFIED` 必须同时有有效 `materialLocator` 和至少一条带 `dataset`、`recordId`、`observedAt` 的外部证据。
- 主体不唯一、工具失败、无外部记录、期间不一致或口径无法对齐时输出 `INSUFFICIENT`。
- 同主体、同指标、同期间和同口径的证据明确不一致时输出 `CONFLICT`。
- 风险是 `riskFlags`，不能替代三种核验状态。
- 不把 `companyId` 当作统一社会信用代码；两者必须按证据字段分别保存。
- 不调用六工具之外的外部能力，不修改任何企业业务数据。

## References 使用规则

运行时会在冻结目录完整 hash 校验通过后，按相对路径固定顺序将以下文件与本正文一起注入。必须执行其中规则，不得假设需要另一个读取工具：

- `references/claim-normalization.md`：用于主张边界、主体简称消歧、期间和金额单位归一化。
- `references/evidence-rules.md`：用于证据比较、三态判定及否定性风险主张处理。

详细规则只维护在对应 reference 中；本文件只保留执行顺序和不可绕过的结果约束。
