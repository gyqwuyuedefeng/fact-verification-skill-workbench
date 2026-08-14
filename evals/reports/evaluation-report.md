# 企业材料事实核验对照评测报告

## 同条件锁定

- 数据集版本：public-tech-2024-v4
- 数据集识别值：1c84a8f165e818cd2b85d757d6b6a59952384aa9f1f1711b3aad413d7fd860c8
- 模型配置识别值：8c7a0e5a62ac53ad181bc8f42752932282968d656b10647388eadeed9b18ac0c
- 模型采样参数：temperature=0.0, topP=1.0, seed=20260812, parallelToolCalls=false, maxTokens=8192, enableThinking=false
- Agent 运行时识别值：3d44d9579ff7201abbf24290d06851f6c190806c42f265328f0df4aaf2976ae9
- 工具契约识别值：db7803975744def97a65246d82aa9bef9fb011aeedfe84d5cce2854bb2931f43
- 证据快照识别值：7d0cd576471cbe4d449664835c96dcad6312cb4df9ad03799a124f8a317f60a5
- 输出契约识别值：98f97e66b832ce51da86d82d787ea94862ff1b32b327c9c4dfae12c5754e811d

## 四项核心指标

| 变体 | 准确率 | 任务完成率 | 稳定性 | 人工介入率 |
|---|---:|---:|---:|---:|
| 48a30585-26c5-4d76-b3c6-4d6fc3cf6c90 | 30/30 (100.00%) | 30/30 (100.00%) | 10/10 (100.00%) | 7/30 (23.33%) |
| BASELINE | 7/30 (23.33%) | 30/30 (100.00%) | 10/10 (100.00%) | 24/30 (80.00%) |
| 3d537f34-d5ad-4d2f-9ebb-9c00a35241fc | 27/30 (90.00%) | 30/30 (100.00%) | 10/10 (100.00%) | 8/30 (26.67%) |

指标定义：

- completionRate：时限内完成并产生合法结果的样本数 / 样本总数
- stability：同条件三次运行主体和结论一致的抽样数 / 稳定性抽样总数
- accuracy：主体、核验结论和核心证据均正确的金标主张数 / 金标主张总数
- humanInterventionRate：主动请求确认或发布前必须修正的样本数 / 样本总数

## Candidate 门禁

结论：PASS

- [x] sample-count：本批次评测数据集不少于 30 条
- [x] conditions-locked：同条件清单完整锁定
- [x] accuracy-non-regression：Candidate 正确主张数不得低于 Stable
- [x] completion-non-regression：Candidate 完成任务数不得低于 Stable
- [x] stability-non-regression：Candidate 三次一致样本数不得低于 Stable
- [x] intervention-non-regression：Candidate 人工介入样本数不得高于 Stable
- [x] declared-failure-fixed：至少修复一条变更说明中声明的 Stable 失败样本
- [x] no-new-hard-failure：不得新增主体误配或无证据断言

## 单样本结果

共 30 条；机器可读 JSON 保留每个变体的原始输出、三次稳定性结果和评分。
