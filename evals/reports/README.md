# 比赛报告生成区

此目录只接收由工作台在真实公司模型、测试 PostgreSQL 和 dev 同源 Elasticsearch 全部可用后生成的不可覆盖产物：

- `evaluation-report.md` 与 `evaluation-report.json`；
- Run Manifest 和失败样本明细；
- Stable/Candidate 版本卡；
- 真实材料影子复核、晋升与回滚证据。

当前目录中的主报告来自真实公司模型、测试 PostgreSQL 和 dev 同源 Elasticsearch 联调；`historical/` 与 `rejected/` 分别保存运行时变化前的历史 PASS 和门禁未通过批次，不能替代目录根部的当前报告。
