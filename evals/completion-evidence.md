# 规格完成证据

核对时间：2026-08-13（Asia/Shanghai）

本文件按 `spec.md` 当前实际数量逐条核对 45 条 FR 与 16 条 SC。它是证据索引，不复制评测原始事实。

## 证据简称

- E1：`competition-replay/evidence/`，真实 Chromium 页面、文本和七类附件截图与机器可读结果。
- E2：`reports/evaluation-report.md`、`evaluation-report.json`、`run-manifest.json`，当前运行时三版本 30 条正式评测。
- E3：`reports/stable-version-card.json`、`candidate-version-card.json`、`skill-version-comparison.json`。
- E4：`reports/release-evidence.md`、`release-evidence.json`，影子 PASS/FAIL、晋升、回滚与七步追加历史。
- E5：`model-compatibility-report.md`，公司千问模型与固定参数兼容性。
- E6：`mcp-integration-report.md` 与 `competition-replay/evidence/mcp-live-matrix.json`，六工具、十二索引和只读协议边界。
- E7：Maven 全量 `clean install`：backend 114、MCP 13，共 127 个测试，0 failure/0 error；前端 19/19 测试、类型检查、lint、build 通过。
- E8：`demo-materials/`、`dataset.jsonl`、`manifest.json`、`competition-replay/prompts.md` 和手工复演文档。

## Functional Requirements

| 需求 | 状态 | 完成证据 |
|---|---|---|
| FR-001 | PASS | E1 覆盖 PDF、DOCX、PPTX、Markdown、TXT、XLSX、CSV；解析器单测覆盖批准的 legacy 分支。 |
| FR-002 | PASS | E1 显示文件/快照 hash；E2 锁定全部 document snapshot hash。 |
| FR-003 | PASS | E1 与文档解析单测覆盖页、段落、slide、行、sheet/单元格、CSV 行定位。 |
| FR-004 | PASS | Excel 演示材料及解析单测核对表头、坐标、公式、显示值、单位和期间。 |
| FR-005 | PASS | E1 结构化主张卡与 E2 单样本输出包含主体、指标、期间、操作符、数值、单位。 |
| FR-006 | PASS | E6 六个只读工具及统一 dataset/recordId/retrievedAt envelope。 |
| FR-007 | PASS | E1/E2 只出现 VERIFIED、CONFLICT、INSUFFICIENT，riskFlags 独立。 |
| FR-008 | PASS | 统一 schema 与业务不变式测试拒绝无材料位置或无外部证据的 VERIFIED。 |
| FR-009 | PASS | E3 固定 `company-material-fact-check` 家族，共享输入、工具、schema 和评分。 |
| FR-010 | PASS | Skill 页面展示 DRAFT/CANDIDATE/STABLE/ARCHIVED；冻结内容 hash 门禁与篡改测试通过。 |
| FR-011 | PASS | E3 包含版本号、父版本、content hash、变更和版本卡。 |
| FR-012 | PASS | E2 BASELINE 使用同一公司模型和短通用指令，不加载 Skill，无第三方模型。 |
| FR-013 | PASS | E2 一次包含 BASELINE、Stable、Candidate。 |
| FR-014 | PASS | E2 Run Manifest 锁定数据、顺序、模型、参数、运行时、工具、证据和输出契约。 |
| FR-015 | PASS | E2 评测前形成单一 evidence snapshot hash，全部变体重放。 |
| FR-016 | PASS | E8 `public-tech-2024-v3` 30 条，数据合同测试 6/6。 |
| FR-017 | PASS | E2 准确率保留定义、分子和分母。 |
| FR-018 | PASS | E2 完成率保留 120 秒时限、定义、分子和分母。 |
| FR-019 | PASS | E2 stabilityRuns=3，机器报告保留三次原始输出。 |
| FR-020 | PASS | E2 人工介入率按主动请求或必须修正样本计算。 |
| FR-021 | PASS | 历史评测不可覆盖；E2 保留指标、输出、评分、失败和修正。 |
| FR-022 | PASS | 管理页和 E2 同时导出 Markdown/JSON。 |
| FR-023 | PASS | E2 八项 Candidate 门禁全部通过，1 胜、0 负、29 平。 |
| FR-024 | PASS | E4 为真实任务后台复制；普通页截图不含 Candidate/SHADOW。 |
| FR-025 | PASS | E4 每个影子条目保存独立 PRIMARY/SHADOW 与两个固定版本。 |
| FR-026 | PASS | E4 含人工 PASS 1、FAIL 1；无 PASS 的晋升请求被拒绝。 |
| FR-027 | PASS | E4 验证晋升后新任务、回滚后新任务和旧历史版本不变。 |
| FR-028 | PASS | E4 七个 release revision 追加保存原因、操作人、时间和前后绑定。 |
| FR-029 | PASS | 工作台七表、文件目录和 `test` schema 独立；无个人开发机数据库依赖。 |
| FR-030 | PASS | E6 企业事实仅 ES `_search`；数据库只读配置和静态门禁通过。 |
| FR-031 | PASS | DatabaseBoundaryVerifier 启动校验数据库名/schema；对应测试 3/3。 |
| FR-032 | PASS | SensitiveDataGuardTest、脱敏错误和导出 hash 通过；报告无地址、密码、token 或文件全文。 |
| FR-033 | PASS | 最终重启后管理页重新读取全部评测、版本、影子和当前 ROLLBACK 状态。 |
| FR-034 | PASS | E4 注册关联不可变 Candidate、PASS 评测和版本卡；DRAFT 状态门禁测试通过。 |
| FR-035 | PASS | 首轮 BASELINE+Candidate `bc9638c9...` PASS 后建立初始 Stable；后续才开启影子。 |
| FR-036 | PASS | E1 覆盖纯文字、单文件、组合输入、Enter/按钮和空输入禁用。 |
| FR-037 | PASS | 普通页仅 BASELINE/Stable；E1 页面探测无 Candidate/历史版选择。 |
| FR-038 | PASS | E1 实时连续任务卡含 01 输入快照、02 实际事件、03 主张与稳定失败码。 |
| FR-039 | PASS | E1 导航分普通使用和管理控制台三页，明确唯一演示账号且无虚假权限声明。 |
| FR-040 | PASS | E1 管理评测页列出并切换不可覆盖历史，展示批次状态与指标。 |
| FR-041 | PASS | 三版本自动化验证 Stable 至少参与两次，并从版本汇总回到原批次。 |
| FR-042 | PASS | E2 版本对比绑定共同评测 ID，展示四指标 delta 与逐样本胜负。 |
| FR-043 | PASS | E4 管理页按时间排序并支持企业/文件、版本、复核状态筛选；明确不显示影子准确率。 |
| FR-044 | PASS | E3 同时保存确定性 diff 和标记“模型生成、仅供审核参考”的摘要，摘要不进门禁。 |
| FR-045 | PASS | E8 有 8 份模拟材料，覆盖多主张、主体歧义和电子表格，敏感内容测试通过。 |

## Success Criteria

| 标准 | 状态 | 完成证据 |
|---|---|---|
| SC-001 | PASS | E1 七种文件实际上传、解析、定位与 Agent 结果均完成。 |
| SC-002 | PASS | E8 30 条覆盖五类工具、歧义、缺失、期间/单位和冲突。 |
| SC-003 | PASS | E2 三变体 30 条均有原始输出和评分。 |
| SC-004 | PASS | E2 四指标均含定义、分子、分母、百分比和失败样本。 |
| SC-005 | PASS | E2 单一 evidence snapshot hash，所有规范请求冻结后重放。 |
| SC-006 | PASS | schema/业务门禁与正式门禁均通过，无证据 VERIFIED 为 0。 |
| SC-007 | PASS | E3 冻结版本均可由版本号和 hash 唯一定位并关联版本卡。 |
| SC-008 | PASS | E3/E4 连续完成冻结、评测、注册、影子、复核、晋升、回滚。 |
| SC-009 | PASS | ShadowRunIsolationTest 与 E4 证明影子失败不反写 PRIMARY。 |
| SC-010 | PASS | E4 保存晋升后、回滚后两个首任务的固定 PRIMARY runId。 |
| SC-011 | PASS | 最终重启后重新打开 E2/E3/E4 全部持久化状态。 |
| SC-012 | PASS | E1 纯文字和文件均展示 01/02/03，普通页暴露 Candidate/SHADOW 次数为 0。 |
| SC-013 | PASS | 管理页已保存多个批次；Stable 版本汇总参与次数不少于 2 且可回到原批次。 |
| SC-014 | PASS | E2 直接对比显示共同评测 ID；不同锁定条件不可比较由服务测试覆盖。 |
| SC-015 | PASS | E3 始终展示确定性差异，成功摘要明确“仅供审核参考”。 |
| SC-016 | PASS | E8 的 8 份材料不含个人信息、凭据或内部文件原文。 |

## 最终判断

45/45 FR 与 16/16 SC 均有当前代码、自动测试或真实运行产物支撑。比赛核心交付物——可运行 Agent、30 条金标集、同条件评测报告、版本卡、升级说明、影子/晋升/回滚证据——均可从上述路径复查。
