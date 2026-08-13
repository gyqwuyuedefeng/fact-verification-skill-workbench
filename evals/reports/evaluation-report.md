# 企业材料事实核验对照评测报告

## 同条件锁定

- 数据集版本：public-tech-2024-v3
- 数据集识别值：76af012b1a946291a8384088b64566b67db0ea1c2186bd3981affde55bbdf5a7
- 模型配置识别值：8c7a0e5a62ac53ad181bc8f42752932282968d656b10647388eadeed9b18ac0c
- 模型采样参数：temperature=0.0, topP=1.0, seed=20260812, parallelToolCalls=false, maxTokens=8192, enableThinking=false
- Agent 运行时识别值：289e9033fec892d3a0006b01c32ca2b0cee5febe9d84e55aa843b4b3c429b59a
- 工具契约识别值：db7803975744def97a65246d82aa9bef9fb011aeedfe84d5cce2854bb2931f43
- 证据快照识别值：d8e71bf3d46d7dc24c9e1241580a1770df31ddbf4f5137ed03614bf08916d217
- 输出契约识别值：98f97e66b832ce51da86d82d787ea94862ff1b32b327c9c4dfae12c5754e811d

## 四项核心指标

| 变体 | 准确率 | 任务完成率 | 稳定性 | 人工介入率 |
|---|---:|---:|---:|---:|
| fc91c90c-4c10-4fc1-9c9e-1fa5caa9ea40 | 27/30 (90.00%) | 30/30 (100.00%) | 10/10 (100.00%) | 7/30 (23.33%) |
| BASELINE | 26/30 (86.67%) | 30/30 (100.00%) | 10/10 (100.00%) | 7/30 (23.33%) |
| b9824010-ee1b-4cea-be32-a7e732297a8c | 28/30 (93.33%) | 30/30 (100.00%) | 10/10 (100.00%) | 7/30 (23.33%) |

指标定义：

- completionRate：时限内完成并产生合法结果的样本数 / 样本总数
- stability：同条件三次运行主体和结论一致的抽样数 / 稳定性抽样总数
- accuracy：主体、核验结论和核心证据均正确的金标主张数 / 金标主张总数
- humanInterventionRate：主动请求确认或发布前必须修正的样本数 / 样本总数

## Candidate 门禁

结论：PASS

- [x] sample-count：门禁数据集不少于 30 条
- [x] conditions-locked：同条件清单完整锁定
- [x] accuracy-non-regression：Candidate 正确主张数不得低于 Stable
- [x] completion-non-regression：Candidate 完成任务数不得低于 Stable
- [x] stability-non-regression：Candidate 三次一致样本数不得低于 Stable
- [x] intervention-non-regression：Candidate 人工介入样本数不得高于 Stable
- [x] declared-failure-fixed：至少修复一条变更说明中声明的 Stable 失败样本
- [x] no-new-hard-failure：不得新增主体误配或无证据断言

## 单样本结果

共 30 条；机器可读 JSON 保留每个变体的原始输出、三次稳定性结果和评分。
