# 比赛测试与复演入口

本目录集中保存比赛演示时可复制的提示词、操作顺序和预期现象。测试数据本身继续保留在 `evals/` 的唯一权威位置，避免复制后产生两套不一致数据。

## 数据在哪里

| 用途 | 文件 | 怎么使用 |
|---|---|---|
| 普通用户 Markdown 附件演示 | `../demo-materials/01-模拟星河智造经营简报.md` | 对话核验页上传；预期因企业是虚构主体而给出“证据不足”，不能把附件自证为真 |
| 普通用户 TXT 主体歧义演示 | `../demo-materials/02-同名主体核验.txt` | 对话核验页上传；预期停止错误拼接并要求人工确认主体 |
| 普通用户 CSV 表格演示 | `../demo-materials/03-模拟企业经营指标.csv` | 对话核验页上传；预期识别行列位置、年份和“万元”单位 |
| Word/PDF 定位演示 | `../demo-materials/06-模拟企业尽调材料.docx` / `.pdf` | 同一份虚构材料验证段落和页码 locator |
| PPT 定位演示 | `../demo-materials/07-模拟企业融资说明.pptx` | 验证幻灯片页码和多页文本提取 |
| Excel 公式演示 | `../demo-materials/08-模拟企业财务台账.xlsx` | 验证工作表、单元格、万元单位和公式文本保留 |
| 影子 PASS 材料 | `../demo-materials/04-影子灰度-科大讯飞经营事实.md` | 开启影子后以 Stable 提交；正式答案仍只来自 Stable，后台产生 Candidate 对照 |
| 影子 FAIL 按钮材料 | `../demo-materials/05-影子灰度-金山办公风险事实.md` | 演示否定性风险覆盖边界和人工 FAIL，不把影子结果当金标准确率 |
| 现场快速评测集 | `../live-smoke-dataset.jsonl` + `../live-smoke-manifest.json` | 管理评测页默认选择；3 条真实样本，完整调用模型、Agent、MCP 与 ES，但不能注册或发布 |
| 管理员固定评测集 | `../dataset.jsonl` | 管理评测页自动读取，不需要逐条上传 |
| 评测集版本与固定顺序 | `../manifest.json` | 锁定 `public-tech-2024-v4`、30 条样本及运行顺序；v3 与更早缺陷资产只保留历史审计 |

## 启动测试预览

在项目根目录显式选择一种模式；不传模式会直接报错，避免误连环境：

```bash
# WSL 开发：使用既有 127.0.0.1 受管转发
bash evals/competition-replay/start-test-preview.sh wsl

# 公司内网审核：直连既有测试配置中的真实内网地址
bash evals/competition-replay/start-test-preview.sh intranet
```

`wsl` 模式固定使用测试 PostgreSQL、dev ES 和公司模型的本机受管转发；`intranet` 模式从 FireLM 测试配置读取测试 PG 和当前公司模型真实地址，从 metastart dev 配置读取真实 ES 地址。两种模式均固定使用 `test` profile、`register-enabled=false` 和 `kjjr_inx_brain.test`，且本机 MCP/后端/前端端口不变。

脚本不会把用户名、密码或模型密钥复制到复演目录或打印到终端。直接启动，出现 `PREVIEW_READY` 后打开 `http://127.0.0.1:15173` 再执行下方场景；结束时在启动终端按 `Ctrl+C`，脚本精确关闭本次三个进程。日志按模式保存在 `.tmp/fact-verification-logs/live-preview-wsl/` 或 `live-preview-intranet/`。

自动化入口（另开终端执行）：

```bash
# 非破坏、可重复的“马上能测”浏览器 + MCP 回归
bash evals/competition-replay/run-quick-browser-tests.sh

# 在干净发布状态上执行完整比赛链路；真实模型评测会持续输出进度
bash evals/competition-replay/run-full-competition-browser-tests.sh
```

本轮从零演示已经使用 Playwright MCP 逐页执行真实点击、上传、确认、取消、刷新和下载，并把页面快照保存在工作区 `.playwright-mcp/`。仓库同时保留同版本 Playwright Browser API 脚本，用于无人值守重复回归；两者使用相同页面选择器、网络响应和失败条件。

## 最短演示顺序

先做“马上能测”，确认产品交互和核验原则；再做“完整比赛演示”，证明同条件评测、版本注册、影子灰度、晋升和回滚。

### 一、马上能测（约 5 分钟）

1. 打开“对话核验”，选择 `BASELINE`，粘贴 `prompts.md` 的场景 1。
2. 检查页面实时出现 01 材料解析、02 执行轨迹、03 核验主张。
3. 粘贴场景 2，检查错误金额不能被判为已核实。
4. 先上传 Markdown、TXT、CSV 三份模拟附件；完整格式复验再上传 Word、PDF、PPT、Excel，使用 `prompts.md` 场景 3—5、8—10。
5. 若已经存在 Stable，再用相同输入切换 `Stable` 重跑；普通用户只看到当前选择模式的正式结果。
6. 打开“管理评测”，默认选择“现场快速评测（3 条）”，选择 Candidate 后运行；它用于现场证明三变体真实可跑，但页面和后端都禁止用它注册或发布。

快速集固定复用正式金标中的三条原始记录，内容和评分标准没有改写：

1. `iflytek-finance`：科大讯飞 2024 年营业收入，预期“已核验（VERIFIED）”。
2. `iflytek-risk`：科大讯飞 2024 年“无任何风险记录”的否定性断言，预期“证据不足（INSUFFICIENT）”。
3. `yonyou-period-conflict`：用友网络 2023 年营业收入与证据金额冲突，预期“存在冲突（CONFLICT）”。

“查看历史结果”只读取并展示已经存在的批次，不会再次发起评测，也不会改变右侧“发起新评测”的数据集选择。

### 二、完整比赛演示（约 15 分钟）

1. 在 Skill 实验室建立并冻结初始 Candidate。
2. 管理评测页先用默认 3 条快速集确认链路，再切换“正式完整评测（30 条）”运行 `BASELINE + Candidate`；只有正式批次通过后才能将 Candidate 建立为初始 Stable。
3. 从 Stable 克隆新 DRAFT，加入“否定性风险主张不能因空结果直接判真”的规则，冻结为新 Candidate。
4. 管理评测页可先跑三条快速反馈，再切换正式 30 条运行 `BASELINE + Stable + Candidate`，查看四指标、版本汇总、单样本下钻和与上一版对比。
5. 注册通过门禁的 Candidate，开启影子；普通用户仍只收到 Stable 正式结果。
6. 管理员在发布管理页查看同一输入的 PRIMARY/SHADOW 对照，人工标记 PASS。
7. 晋升 Candidate，提交一条新任务确认新 Stable 生效；随后回滚并确认后续任务恢复到旧 Stable。

## 评审时怎么讲

- `BASELINE`：同一个公司千问模型，不加载专用 Skill；它就是“通用模型”基线。
- `Stable`：当前正式 Skill，只生成用户可见的正式结果。
- `Candidate`：待评测/待灰度的新版本，只在管理员离线评测或影子运行中出现。
- 三个变体共享同一数据集、同一输入、同一模型、同一工具、同一证据快照和同一生成参数；生成参数显式包含 `enableThinking=false` 与 `maxTokens=8192`，因此差异可归因到 Skill。
- 核心指标是准确率、任务完成率、稳定性、人工介入率；版本之间的结果要看总体指标，也要下钻失败样本。
- 快速 3 条与正式 30 条使用同一执行和评分代码；快速集只缩短现场等待，不具有发布资格，正式集才是比赛门禁证据。

正式 `v4` 金标只引用当前六个 MCP 工具可返回的 dev 同源 ES `recordId`；`v3` 及更早版本保留为证据漂移和缺陷修复历史，不再允许新建评测或用于发布分数。

## 文件说明

- `现场测试执行卡.md`：测试数据位置、可直接复制的首选提示词和两阶段演示顺序。
- `手工复演记录模板.md`：现场逐项填写实际结果、关键版本/评测 ID、截图和异常修复记录。
- `prompts.md`：可直接复制到对话框的测试提示词和每条预期现象。
- `manual-runbook.md`：比赛现场逐步操作和检查清单。
- `run-quick-browser-tests.sh`：对话、冲突、七类附件和六 MCP 工具自动化入口。
- `generate-demo-documents.py`：重建 Word、PDF、PPT、Excel 四份二进制模拟附件。
- `run-full-competition-browser-tests.sh`：Skill、两轮正式评测、影子、晋升与回滚自动化入口。
- `evidence/`：真实 Chromium 截图、页面探测和机器可读执行状态。
- `../reports/`：正式评测报告、Run Manifest、失败样本、版本卡和发布证据。
