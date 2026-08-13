# 环境依赖、数据表与 MCP 数据字典

本文记录 `fact-verification-skill-workbench` 当前实际使用的数据源、连接边界、PostgreSQL 表、MCP 工具与 Elasticsearch 索引。内容面向比赛审核和内部联调，可随 public 仓库公开。

> 安全说明：本文有意列出内网 IP 和端口，但不保存真实用户名、密码、API Key、Token 或私钥。认证值只能从既有受控配置注入，不能复制进本仓库或 Git 历史。

## 1. 数据流总览

```text
Vue 3 浏览器
  -> backend :19090
      -> PostgreSQL kjjr_inx_brain.test
         保存任务、运行、主张、Skill 版本、评测、证据快照和发布历史
      -> 公司千问 OpenAI-compatible /v1/chat/completions
      -> AgentScope Java MCP client
         -> mcp-server :19091/mcp（原生 Streamable HTTP）
             -> PostgreSQL test.evidence_snapshot（只读快照优先）
             -> Elasticsearch（快照未命中时查询十二个固定索引）
```

浏览器任务进度使用 backend 的业务事件流。它不是 MCP 旧 SSE；Agent 到 MCP 只有单一 `/mcp` Streamable HTTP 链路。

## 2. 当前连接地址

### 2.1 公司内网直连

| 依赖 | 地址 | 本项目边界 | 用途 |
|---|---|---|---|
| PostgreSQL 测试库 | `jdbc:postgresql://192.168.201.203:5432/kjjr_inx_brain?currentSchema=test` | 数据库 `kjjr_inx_brain`，schema 强制为 `test` | backend 读写工作台七表；MCP 只读查询证据快照 |
| Elasticsearch 节点 1 | `http://192.168.53.60:9200` | 固定索引和 `_source` 字段白名单 | 企业事实证据查询 |
| Elasticsearch 节点 2 | `http://192.168.53.61:9200` | 同上 | 集群节点 |
| Elasticsearch 节点 3 | `http://192.168.53.62:9200` | 同上 | 集群节点 |
| 公司千问 | `http://192.168.99.26:8080/v1/chat/completions` | OpenAI-compatible Chat Completions | BASELINE、Stable、Candidate 共用模型 |
| 当前模型 ID | `fire2.0-30B` | 运行时由 `LOCAL_MODEL_ID` 注入 | 同条件评测锁定模型标识 |

现有 FireLM 配置中的 `DB_SCHEMA` 是 `firelm`，但本项目不会使用该 schema。启动脚本会重新组装 JDBC URL 并强制 `currentSchema=test`；backend 和 MCP 还会在启动时校验数据库名/schema，边界不匹配即拒绝启动。

### 2.2 WSL 开发转发

| 依赖 | WSL 使用地址 | 内网目标 | 当前声明状态 |
|---|---|---|---|
| PostgreSQL | `127.0.0.1:45432` | `192.168.201.203:5432` | `mappings.conf` 中启用 |
| ES 节点 1 | `127.0.0.1:29200` | `192.168.53.60:9200` | 启用 |
| ES 节点 2 | `127.0.0.1:29201` | `192.168.53.61:9200` | 启用 |
| ES 节点 3 | `127.0.0.1:29202` | `192.168.53.62:9200` | 启用 |
| 公司千问 | `127.0.0.1:48080` | `192.168.99.26:8080` | 启动脚本使用该地址；通用 `mappings.conf` 当前标记为 disabled，使用前必须确认本机已有可用转发 |

只读检查命令：

```bash
/mnt/g/Obsidian/code/01_System_Core/scripts/vpn-forward/check.sh --tag database
/mnt/g/Obsidian/code/01_System_Core/scripts/vpn-forward/check.sh --tag search-dev
curl -fsS http://127.0.0.1:48080/v1/models
```

### 2.3 本项目本机端口

| 组件 | 地址 |
|---|---|
| Vue 前端 | `http://127.0.0.1:15173` |
| backend | `http://127.0.0.1:19090` |
| MCP Server | `http://127.0.0.1:19091/mcp` |
| backend health | `http://127.0.0.1:19090/actuator/health` |
| MCP health | `http://127.0.0.1:19091/actuator/health` |

## 3. 账号、密码与配置来源

真实认证值不进入本仓库。下表列出本项目接受的变量和内部环境的权威来源；“值”列故意不公开。

| 连接 | 本项目变量 | 内部权威配置键 | 值/处理方式 |
|---|---|---|---|
| backend PostgreSQL 账号 | `APP_DB_USERNAME` | `ai-firelm/backend/.env.staging` 的 `DB_USER` | 禁止公开，由统一启动脚本注入 |
| backend PostgreSQL 密码 | `APP_DB_PASSWORD` | 同文件的 `DB_PASSWORD` | 禁止公开，由统一启动脚本注入 |
| MCP 快照库账号 | `SNAPSHOT_DB_USERNAME` | 当前复用 `DB_USER` | 连接池和事务强制只读；生产化时宜使用独立只读角色 |
| MCP 快照库密码 | `SNAPSHOT_DB_PASSWORD` | 当前复用 `DB_PASSWORD` | 禁止公开 |
| ES 账号 | `ES_USERNAME` | `metastart/application-dev.yml` 的 `hsmap.elasticsearch.userName` | 禁止公开，由统一启动脚本注入 |
| ES 密码 | `ES_PASSWORD` | 同节点的 `password` | 禁止公开，RestClient 运行时生成 Basic Authorization |
| 模型 ID | `LOCAL_MODEL_ID` | `ai-firelm/backend/.env.staging` 的 `LOCAL_MODEL_ID` | 当前为 `fire2.0-30B`，非秘密 |
| 模型 API Key | `LOCAL_MODEL_API_KEY` | 同文件同名键 | 可为空；非空时禁止公开 |

公开仓库只提供 [.env.example](../.env.example)。内部人员启动时直接使用统一脚本从权威文件读取；外部 clone 必须自行准备合法的测试环境变量。

## 4. PostgreSQL 表

业务表全部位于 `kjjr_inx_brain.test`，由 backend 的 Flyway 管理。MCP 不创建新表，只对 `test.evidence_snapshot` 执行 SELECT；ES 企业业务数据不会复制为第二套业务库。

### 4.1 表关系

```text
verification_task 1 -> N verification_run 1 -> N claim
verification_task ---- evidence_snapshot（通过 owner_type/owner_id 逻辑关联）
evaluation_run  ------ evidence_snapshot（通过 owner_type/owner_id 逻辑关联）
skill_version 1 -> N skill_version（parent_version_id 版本谱系）
skill_version <-> evaluation_run（registered_evaluation_id）
release_binding -> skill_version / evaluation_run（追加式发布历史）
```

当前 `verification_run` 数据库约束仍是每个 task/run_type 唯一，所以同一任务最多一条 PRIMARY 和一条 SHADOW。

### 4.2 七张业务表

| 表 | 主要用途 | 关键字段与约束 |
|---|---|---|
| `test.verification_task` | 一次用户对话/文件核验任务及解析快照 | `request_id` 幂等唯一；支持 `TEXT/FILE/COMBINED`；保存文件 hash、文档快照、状态、错误摘要和证据快照 ID |
| `test.verification_run` | PRIMARY/SHADOW 的一次 Agent 运行 | 固定模型/工具/输出 schema hash；`variant_type=BASELINE` 时无 Skill 版本且只能 PRIMARY；Skill 运行必须绑定 `skill_version_id`；保存工具调用、模型用量、耗时和影子复核 |
| `test.claim` | 运行产生的逐条可核验主张 | `(run_id, ordinal)` 唯一；保存材料定位、归一化主张、主体、`VERIFIED/CONFLICT/INSUFFICIENT`、风险标记、证据和人工修正 |
| `test.skill_version` | DRAFT/CANDIDATE/STABLE/ARCHIVED 版本资产 | 保存 `SKILL.md`、references、允许工具、输出 schema、content hash、父版本、版本卡和注册评测；冻结内容不可变 |
| `test.evaluation_run` | 同条件 BASELINE/Skill 评测批次 | 保存 dataset/hash、Run Manifest、参评版本、样本原始结果、四项指标、人工修正、门禁和不可变报告 |
| `test.evidence_snapshot` | MCP 请求/响应冻结与重放 | `(snapshot_id, tool_name, arguments_hash)` 唯一；成功响应或稳定错误二选一；防止不同版本使用不同现场证据 |
| `test.release_binding` | 版本注册、影子、晋升、回滚的追加式状态历史 | `(skill_key, revision)` 唯一；动作仅允许 `INITIALIZE/REGISTER/SHADOW_START/SHADOW_STOP/PROMOTE/ROLLBACK`；保存变更前后状态和人工原因 |

### 4.3 Flyway 元数据表

| 表 | 用途 |
|---|---|
| `test.flyway_schema_history_fact_verification` | 仅记录本项目 migration 版本和校验信息；不是业务表，Flyway clean 被禁用 |

迁移来源：

- `V1__create_fact_verification_workbench.sql`：创建七张业务表、外键、唯一约束和索引。
- `V2__chat_verification.sql`：给既有任务/运行表增加对话输入和 BASELINE 变体字段，没有新增第八张业务表。

## 5. MCP 工具与查询数据

MCP Server 对外只注册以下六个工具，均声明 `readOnlyHint=true`、`destructiveHint=false`、`idempotentHint=true`。模型不能指定索引名、返回字段、排序或数量。

| MCP 工具 | 输入 | 用途 | ES 索引 | 主要数据 |
|---|---|---|---|---|
| `resolve_company` | `query`：企业名称、简称、曾用名或统一社会信用代码 | 先把材料中的主体线索解析为规范 `companyId` 候选 | `ads_lget_company_info`，最多 10 条 | 企业编码/名称/简称/曾用名/统一代码、法人、成立日期、注册资本、状态、地址、范围、行业 |
| `get_company_profile` | `companyId` | 核验企业基本工商资料 | `ads_lget_company_info`，最多 1 条 | 与主体索引相同的基本资料字段 |
| `get_company_financials` | `companyId` | 核验报告期财务指标 | `ads_lget_company_revenue`，最多 10 条，按 `report_year` 降序 | 营收及区间、营收同比、留存利润及区间/同比、营业收入同比、资产负债同比 |
| `get_company_intellectual_property` | `companyId` | 聚合知识产权和产品证据 | `ads_lget_patent_info`、`ads_lget_software_copyright`、`ads_lget_product_info`，每索引最多 20 条 | 专利申请/公开/日期/类型/法律状态；软著登记/批准/名称/类型；产品名称/服务/型号/证书/有效期 |
| `get_company_risks` | `companyId` | 聚合企业风险证据 | `ads_lget_company_lose_trust`、`ads_lget_company_illegal_info`、`ads_lget_company_adm_punish`、`ads_lget_company_tax_arrears_info`，每索引最多 20 条 | 失信执行、违法进出名录、行政处罚、欠税金额/日期/发布机构 |
| `get_company_relationships` | `companyId` | 聚合股东、客户、供应商关系 | `ads_lget_company_sholder`、`ads_lget_company_client`、`ads_lget_company_supply`，每索引最多 20 条 | 股东类型/比例/出资；客户销售额/产品/比例；供应商采购额/产品/比例 |

### 5.1 十二个固定 ES 索引

1. `ads_lget_company_info`
2. `ads_lget_company_revenue`
3. `ads_lget_patent_info`
4. `ads_lget_software_copyright`
5. `ads_lget_product_info`
6. `ads_lget_company_lose_trust`
7. `ads_lget_company_illegal_info`
8. `ads_lget_company_adm_punish`
9. `ads_lget_company_tax_arrears_info`
10. `ads_lget_company_sholder`
11. `ads_lget_company_client`
12. `ads_lget_company_supply`

### 5.2 查询方式

- `resolve_company` 对 `company_name`、`name_before` 使用 `match_phrase`，对 `company_sname.keyword`、`uni_code.keyword` 使用精确 `term`。
- 专利、软著、产品按嵌套字段 `org_info_list.company_code.keyword` 查询。
- 其余工具按 `company_code.keyword` 精确过滤。
- ES 响应只保留服务端固定 `_source` 白名单，并给每条命中补充 `dataset`、ES `_id` 对应的 `recordId` 和 `observedAt`。
- 统一证据来源标记为 `HS_ENTERPRISE_ES`。

## 6. 快照、协议和只读边界

1. backend 为每个运行/评测建立 `snapshotId`，创建独立 AgentScope MCP client，并通过 `X-Evidence-Snapshot-Id` 请求头传递。
2. MCP 规范化唯一工具参数并计算 `argumentsHash`。
3. MCP 先 SELECT `test.evidence_snapshot`；命中即返回冻结响应，绝不访问 ES。
4. 快照未命中时才执行固定 ES `_search`；backend 的记录工具把响应或稳定错误写入快照表。
5. 同条件评测的 BASELINE、Stable、Candidate 共享同一 snapshot ID，避免证据时间漂移。

只读保护包括：

- MCP PostgreSQL 连接池设置 `read-only=true`，连接初始化执行 `SET default_transaction_read_only = on`。
- MCP 代码只包含快照 SELECT 和 ES `_search`，没有业务 PG 数据源或 ES 写 API。
- ES 索引、字段、查询结构和上限由 `EvidenceToolCatalog` 固定。
- MCP 只开放 `/mcp` Streamable HTTP；不存在 `/sse` 或 `/mcp/message` 旧端点。

## 7. 配置和核对入口

| 内容 | 权威文件 |
|---|---|
| backend 数据库/模型/MCP 配置 | `backend/src/main/resources/application.yml` |
| MCP 快照库、ES、Streamable HTTP 配置 | `mcp-server/src/main/resources/application.yml` |
| 七表迁移 | `backend/src/main/resources/db/migration/` |
| 六工具注解 | `mcp-server/.../tool/EnterpriseEvidenceTools.java` |
| 十二索引和字段白名单 | `mcp-server/.../contract/EvidenceToolCatalog.java` |
| WSL/intranet 地址选择 | `evals/competition-replay/start-test-preview.sh` |
| 可公开环境变量模板 | `.env.example` |

启动前可执行脱敏配置检查：

```bash
bash evals/competition-replay/start-test-preview.sh wsl --print-config
bash evals/competition-replay/start-test-preview.sh intranet --print-config
```

输出只能包含地址和非秘密运行参数，不得出现用户名、密码或 API Key。
