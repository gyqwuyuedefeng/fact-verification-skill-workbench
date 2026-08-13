# 企业材料事实核验对照评测报告

## 同条件锁定

- 数据集版本：public-tech-2024-v3
- 数据集识别值：76af012b1a946291a8384088b64566b67db0ea1c2186bd3981affde55bbdf5a7
- 模型配置识别值：8c7a0e5a62ac53ad181bc8f42752932282968d656b10647388eadeed9b18ac0c
- 模型采样参数：temperature=0.0, topP=1.0, seed=20260812, parallelToolCalls=false, maxTokens=8192, enableThinking=false
- Agent 运行时识别值：cdebd185bd457c87361a04d90da525eb0a961fcc0168839d30e39c450d4ffe80
- 工具契约识别值：db7803975744def97a65246d82aa9bef9fb011aeedfe84d5cce2854bb2931f43
- 证据快照识别值：bd3b375c6ec4fd9b7ff6c7b0826a9bc5179a01afaf01381f8a80c71d6691c057
- 输出契约识别值：98f97e66b832ce51da86d82d787ea94862ff1b32b327c9c4dfae12c5754e811d

## 四项核心指标

| 变体 | 准确率 | 任务完成率 | 稳定性 | 人工介入率 |
|---|---:|---:|---:|---:|
| 24e515d5-6d0d-4f9a-b981-b44d8d154a77 | 4/30 (13.33%) | 15/30 (50.00%) | 3/10 (30.00%) | 27/30 (90.00%) |
| BASELINE | 9/30 (30.00%) | 26/30 (86.67%) | 9/10 (90.00%) | 23/30 (76.67%) |

指标定义：

- humanInterventionRate：主动请求确认或发布前必须修正的样本数 / 样本总数
- completionRate：时限内完成并产生合法结果的样本数 / 样本总数
- stability：同条件三次运行主体和结论一致的抽样数 / 稳定性抽样总数
- accuracy：主体、核验结论和核心证据均正确的金标主张数 / 金标主张总数

## Candidate 门禁

结论：FAIL

- [x] sample-count：门禁数据集不少于 30 条
- [x] conditions-locked：同条件清单完整锁定
- [ ] accuracy-non-regression：Candidate 正确主张数不得低于 Stable
- [ ] completion-non-regression：Candidate 完成任务数不得低于 Stable
- [ ] stability-non-regression：Candidate 三次一致样本数不得低于 Stable
- [ ] intervention-non-regression：Candidate 人工介入样本数不得高于 Stable
- [x] declared-failure-fixed：至少修复一条变更说明中声明的 Stable 失败样本
- [ ] no-new-hard-failure：不得新增主体误配或无证据断言

## 单样本结果

共 30 条；机器可读 JSON 保留每个变体的原始输出、三次稳定性结果和评分。
