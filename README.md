# 企业材料事实核验 Skill 工作台

这是比赛用的单人最小 MVP，只完成两个目标：

1. 可运行的企业材料事实核验 Skill/Agent，并交付金标集、评测报告和版本卡。
2. 在公司千问模型不变的前提下，完成 `BASELINE → Stable → Candidate` 同条件评测，以及注册、真实任务影子验证、晋升和回滚。

它不是通用 Agent 平台，不包含模型训练、第三方模型、多租户、Skill 市场、OCR、语音或用户比例灰度。

## 最小架构

- `backend/`：Spring Boot + AgentScope Java 2.0.1，负责材料解析、Agent、评测、Skill 版本和发布状态。
- `mcp-server/`：Spring AI 原生 Streamable HTTP `/mcp`，只提供六个企业证据工具；不存在旧 SSE 握手或 message 双端点。
- `frontend/`：Vue 3 + TypeScript + Pinia，普通入口提供核验对话，管理入口提供评测、Skill 和影子发布三个页面。
- `evals/`：固定 30 条金标、三份纯模拟演示材料及后续导出的报告。
- `skills/company-material-fact-check/`：初始 Skill 和核验规则。

浏览器任务进度使用业务事件流；这与 Agent 到 MCP 的 Streamable HTTP 是两条不同链路。

## 灰度在本项目中的含义

灰度不是“让一部分用户偷偷使用 Candidate”。审核人开启影子后，可在一份真实授权材料上勾选 Candidate：

- PRIMARY 使用当前 Stable，是唯一正式结果。
- PRIMARY 完成后，SHADOW 才在后台使用 Candidate 和同一文档、模型、工具合同、输出合同、证据快照运行。
- SHADOW 超时或失败只记录自身错误，不改变正式任务。
- 人工对照并标记至少一条 PASS 后，Candidate 才允许晋升。
- 回滚只改变后续任务读取的 Stable；历史运行继续保留其启动时版本。

## 配置

复制 `.env.example` 的字段到当前终端环境变量，真实凭据不得写回仓库。比赛数据只落在 `kjjr_inx_brain.test`；MCP 对证据快照库使用只读连接，并仅查询批准的 12 个 ES 索引字段白名单。

当前内网/WSL 地址、认证变量来源、七张业务表、六个 MCP 工具和十二个 ES 索引的完整说明见 [环境依赖、数据表与 MCP 数据字典](docs/环境依赖、数据表与MCP数据字典.md)。公开仓库不保存真实用户名、密码或 API Key。

## 构建与运行

Java 只能使用 hsmap 的 WSL Maven 安全包装器：

```bash
/mnt/g/Obsidian/code/01_System_Core/scripts/maven/run-wsl.sh \
  --project /mnt/f/IdeaProjects/hsmap/standardized-products/fact-verification-skill-workbench \
  --log /mnt/f/IdeaProjects/hsmap/.tmp/fact-verification-logs/maven-clean-install.log \
  -- clean install
```

前端：

```bash
cd frontend
npm ci
npm run type-check
npm run test:unit
npm run build
```

### 统一启动预览

启动验收统一使用 `test` profile，且 Nacos `register-enabled=false`。必须显式选择一种依赖连接模式，不传模式会直接报错：

```bash
cd /mnt/f/IdeaProjects/hsmap/standardized-products/fact-verification-skill-workbench

# WSL 开发：测试 PG、dev ES、公司模型走既有 127.0.0.1 受管转发
bash evals/competition-replay/start-test-preview.sh wsl

# 公司内网审核：测试 PG、dev ES、公司模型直连既有配置中的真实内网地址
bash evals/competition-replay/start-test-preview.sh intranet
```

两种模式只改变三个外部依赖的地址，MCP、后端和前端始终在本机使用 `19091`、`19090`、`15173` 端口。`intranet` 模式从 `ai-firelm/backend/.env.staging` 读取测试 PG 和当前公司模型地址，从 metastart `application-dev.yml` 读取 dev ES 地址；PG schema 无论哪种模式都强制为 `test`。用户名、密码和可选模型密钥只注入子进程，不会打印或复制到本项目。

启动前可先查看脱敏后的生效配置，不会启动服务：

```bash
bash evals/competition-replay/start-test-preview.sh wsl --print-config
bash evals/competition-replay/start-test-preview.sh intranet --print-config
```

出现 `PREVIEW_READY` 后打开 `http://127.0.0.1:15173`。结束时在启动终端按 `Ctrl+C`，脚本会按本次 PID 精确关闭前端、后端和 MCP。日志分别写入 `/mnt/f/IdeaProjects/hsmap/.tmp/fact-verification-logs/live-preview-wsl/` 或 `live-preview-intranet/`。

完整的数据源和 `/mcp` 协议边界见 [环境依赖、数据表与 MCP 数据字典](docs/环境依赖、数据表与MCP数据字典.md)；页面演示步骤见下节，启动与关闭统一使用上述脚本。

## 比赛演示顺序

1. 在普通“事实核验对话”中输入文字、上传 `evals/demo-materials/` 文件或两者组合，选择 BASELINE/Stable，展示每张任务卡的 01 输入快照、02 实时轨迹与 03 正式主张。
2. 在管理员“对照评测”中切换历史批次、版本汇总和版本对比，展示固定 30 条金标的四项指标、同条件锁、胜负样本与 Run Manifest。
3. 在管理员“Skill 版本”中冻结 Candidate，查看版本卡，并手动生成与上一版/当前 Stable 的确定性差异和 AI 升级说明。
4. 在管理员“影子与发布”中关联通过的评测并注册，开启影子；人工复核真实材料的后台 Candidate 结果，至少一条 PASS 后晋升。
5. 运行一个新任务证明新 Stable 生效，再执行回滚并查看追加式历史；影子真实材料没有金标，页面不得显示准确率。

只有真实公司模型、测试 PG 和 dev 同源 ES 的内网联调完成后，才能把 `evals/reports/` 中的报告作为比赛最终证据。
