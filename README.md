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

## 演示数据管理与两条演示路径

管理员导航中的“演示数据”打开 `/admin/demo-state`。该页面及其 `/api/admin/demo-state/**`
接口只在 `test` profile 下由 `workbench.demo-admin.enabled=true` 开启，不是生产数据管理入口。
同一 test-only 边界还提供固定运维恢复 `POST /api/admin/demo-state/recover-stale`：它只用确认头
“回收遗留任务”处理超过一小时、worker 从未启动且没有其他活动 run 的遗留 PRIMARY；普通任务恢复、任意 ID
或阈值输入不在该接口范围内。

“清空并从第 1 步开始”只删除 `test` schema 中固定的七张比赛业务表：
`verification_task`、`skill_version`、`evaluation_run`、`verification_run`、`claim`、
`evidence_snapshot`、`release_binding`；同时只清空当前 `workbench.storage-root` 下的
`uploads`、`skill-snapshots`、`skill-runtime` 三个受管目录。它不接受调用方指定表名或目录，
也不清理其他 schema、目录或服务。

“导出演示快照（ZIP）”包含上述七表数据和三个目录中的原始企业附件，可能涉及敏感数据。
快照不得提交 Git、上传无关渠道或长期留在项目目录；恢复验收应使用权限 `0600` 的系统临时文件，
核验完成立即删除。

现场只选择以下一条路径，不把两条路径的结果混作同一次运行证据：

### 路径一：从零真实运行

1. 在“演示数据管理”点击“清空并从第 1 步开始”，精确输入确认短语后，从空状态开始。
2. 在“Skill 版本实验室”新建草稿，选择 `01-initial`（初始稳定版）并点击“加载到本地”，再依次“保存草稿（DRAFT）”和“冻结为候选版（CANDIDATE）”。
3. 在“管理评测”使用候选版下拉框运行首次 `BASELINE + CANDIDATE` 评测；在“影子与发布”通过版本和评测下拉框完成初始注册与稳定版（STABLE）建立。
4. 从稳定版克隆草稿，加载 `02-improved`（优化候选版），保存并冻结；随后运行 `BASELINE + STABLE + CANDIDATE` 三版本同条件评测。
5. 在“影子与发布”注册门禁通过的候选版，开启影子；从“事实核验对话”用稳定版提交授权材料，人工复核影子结果并标记“人工通过（PASS）”，再执行“晋升稳定版（STABLE）”和“回滚上一版”。
6. `03-regression`（回归失败版）只用于运行门禁失败证明：预期“管理评测”显示“门禁未通过（FAIL）”，不得注册或晋升。

### 路径二：导入内置固定脱敏状态

1. 确认七张业务表和三个受管目录均为空；在“演示数据管理”点击“导入固定脱敏内置状态”。
2. 使用“管理评测”“Skill 版本实验室”“影子与发布”的页面下拉框查看四个版本、三个评测、影子人工通过/未通过和追加式发布历史。
3. 内置状态是固定脱敏 fixture，导入过程不调用模型，页面结果不是本次现场重新生成；只能用于快速讲解完整链路，不能冒充现场真实运行结果。

只有真实公司模型、测试 PG 和 dev 同源 ES 的内网联调完成后，才能把 `evals/reports/` 中的报告作为比赛最终证据。
