# 评价结果文件说明

## 当前复测

`当前复测/` 对应评测批次 `c5ce810d-93b3-4f6e-9c07-52b0f0faa045`，是当前 Agent 运行时在相同三版本上的最新真实评测：

- `evaluation-report.md`：适合人工阅读的四项指标和八项门禁。
- `evaluation-report.json`：30 条样本、三个变体评分、原始输出和单样本下钻数据。
- `run-manifest.json`：数据集、模型参数、工具、证据、运行时和输出结构的同条件哈希。
- `failed-samples.json`：当前所有变体仍未答对的样本明细。

## 当前发布记录

`发布时版本记录/` 保留本轮 Skill 实际注册与发布的证据：

- 初始 Stable 版本卡绑定正式评测 `2cd5ad24-bddd-4a32-87f0-2252cbf9a2b8`。
- 优化 Candidate 版本卡绑定正式评测 `c5ce810d-93b3-4f6e-9c07-52b0f0faa045`。
- 发布证据记录 Candidate 注册、真实材料影子人工 PASS、晋升、回滚旧版以及恢复评测最优版。

当前评测报告、版本卡和发布证据均来自 2026-08-14 从零复演后的同一版本链路；旧 v3 批次不进入本提交目录。

## 阅读顺序

1. 先读 `当前复测/evaluation-report.md` 看最新成绩。
2. 再读 `当前复测/run-manifest.json` 看同条件锁。
3. 打开 `当前复测/evaluation-report.json` 查看 `sangfor-risk`、`yonyou-risk` 或 `yonyou-basic` 的修复对比。
4. 最后读 `发布时版本记录/release-evidence.md` 和两张版本卡看发布闭环。

## 演示截图

`演示截图/` 提供三张可直接放入邮件或汇报的页面证据：普通用户核验的 01/02/03 过程、三版本同条件评测与八项门禁、影子晋升和回滚历史。截图来自已有真实复演，不代替 JSON 报告。
