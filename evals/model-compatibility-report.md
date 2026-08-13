# 公司千问模型兼容性报告

验证时间：2026-08-13（Asia/Shanghai）

## 结论

公司 OpenAI-compatible 千问模型已通过本 MVP 所需的流式事件、强制主体工具调用、多轮工具回传、统一 JSON 输出和固定参数兼容性门禁。正式评测 `dd376d88-21d2-4566-9e6f-16addf4e29d4` 在当前 Agent 运行时下完成 30 条、三个变体的真实调用并获得 `GATE PASS`。

## 锁定条件

- 数据集：`public-tech-2024-v3`，30 条，识别值见 `reports/run-manifest.json`。
- 变体：BASELINE、Stable `fc91c90c-4c10-4fc1-9c9e-1fa5caa9ea40`、Candidate `b9824010-ee1b-4cea-be32-a7e732297a8c`。
- 固定参数：`temperature=0`、`topP=1`、`seed=20260812`、`parallelToolCalls=false`、`maxTokens=8192`、`enableThinking=false`。
- Agent 运行时识别值：`289e9033fec892d3a0006b01c32ca2b0cee5febe9d84e55aa843b4b3c429b59a`。
- 模型地址、模型密钥和数据源凭据不进入报告，只保存不可逆配置识别值。

## 实际结果

| 检查项 | 结果 | 证据 |
|---|---|---|
| 流式事件 | 通过 | 普通核验页面实际展示 `RUN_CREATED`、`TOOL_STARTED`、`TOOL_ENDED`、`AGENT_RESULT`、`RUN_COMPLETED`；截图见 `competition-replay/evidence/` |
| 外部工具调用 | 通过 | 首轮由原生 tool choice 强制实际调用 `resolve_company`；浏览器回归和评测原始输出均包含工具轨迹 |
| 多轮工具回传 | 通过 | 六类工具结果进入 ReAct 后续推理并形成结构化主张；财务冲突场景实际返回外部证据 |
| 统一 JSON 输出 | 通过 | 所有正式结果先经过 `verification-result.schema.json`；无证据 `VERIFIED` 被硬门禁拒绝 |
| 三次稳定性 | 通过 | BASELINE、Stable、Candidate 在 10 条稳定性抽样上均为 `10/10`，三次原始结果保存在机器可读评测报告 |
| 完成率 | 通过 | 三个变体均为 `30/30`，没有超时或非法输出样本 |
| 格式漂移纠正 | 通过 | 真实 PDF 回归曾复现模型漏必填字段；现在只在同一硬截止时间内重新取证并纠正一次，不由服务端补写字段；相关单元测试 3/3 通过 |

## 四项指标

| 变体 | 准确率 | 完成率 | 稳定性 | 人工介入率 |
|---|---:|---:|---:|---:|
| BASELINE | 26/30 | 30/30 | 10/10 | 7/30 |
| Stable | 27/30 | 30/30 | 10/10 | 7/30 |
| Candidate | 28/30 | 30/30 | 10/10 | 7/30 |

完整输入、参数、原始输出和评分依据见 `reports/evaluation-report.json` 与 `reports/run-manifest.json`。
