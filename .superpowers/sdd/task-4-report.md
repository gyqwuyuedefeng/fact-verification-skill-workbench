# Task 4 实施报告：演示状态检查、完整清空与运行目录隔离

## 交付内容

- 新增 `DemoStateRepository`，仅以固定 SQL 查询和清理 `claim`、`verification_run`、`verification_task`、`evidence_snapshot`、`release_binding`、`skill_version`、`evaluation_run` 七张业务表。
- 清理顺序与真实外键一致：先删除子表，删除 `skill_version` 前先解除 `parent_version_id` 与 `registered_evaluation_id`，最后删除 `evaluation_run`。
- 新增 `ManagedStorageSwap`，只管理 `storageRoot/uploads`、`skill-snapshots`、`skill-runtime`。清空前原子移动至 `.demo-reset/<operationId>`；数据库事务失败时恢复，成功后删除暂存目录；`.gitkeep` 不是运行产物。
- 新增 test-profile 且显式开关控制的 `/api/admin/demo-state/status` 与 `/api/admin/demo-state/reset`。重置要求 `Idempotency-Key` 和固定确认短语。
- 新增 `DemoStateService.requireBlank()`，供后续快照导入在写入前复用空状态检查。
- `.gitignore` 仅新增 `data/.demo-import/` 与 `data/.demo-reset/`；执行前已确认 `git ls-files data` 只跟踪三个既有 `.gitkeep`，未清理现有运行文件。

## TDD 与验证

- RED：使用 feature worktree 的 WSL Maven 安全包装器运行定向测试，因演示模块尚不存在而得到预期 `BUILD FAILURE`。
- GREEN：同一命令通过，`DemoStateApiTest` 3 项、`DemoStateServiceTest` 5 项、`ManagedStorageSwapTest` 1 项，合计 9 项均为 failures=0、errors=0，且输出 `BUILD SUCCESS`。日志：`/mnt/f/IdeaProjects/hsmap/.tmp/fact-verification-logs/task4-green.log`。
- 已执行 `git diff --check`；未使用数据库客户端写入。

## 已知验证边界

- 当前环境未配置完整 `APP_DB_USERNAME` / `APP_DB_PASSWORD`，因此无法在不伪造凭据的前提下启动连接远端 test 数据库的后端进行启动验证；`application-test.yml` 已确认 Nacos `register-enabled: false`，并仅额外开启 `workbench.demo-admin.enabled`。

## 审查修复补充（Task 4）

- `DemoStateService` 现在以调用方的 `Idempotency-Key` 为单进程单用途键：最多保存 64 个不可淘汰键；同键并发请求等待首个结果，同键后续请求返回首次稳定结果，避免在后续导入新数据后再次执行清空。
- reset 使用服务内专属 `TransactionTemplate`，固定 `PROPAGATION_REQUIRES_NEW`；真实事务管理器测试验证独立传播属性、commit 回调及数据库异常时的 rollback，目录暂存只在该事务正常返回后删除。
- 补充 test profile + enabled=true 的控制器正向装配测试；`.gitkeep` 仅在它是普通文件时被忽略，同名目录及其内容判定为非空。
- 覆盖测试文件：`DemoStateServiceTest`（同键复用、并发、REQUIRES_NEW/commit、回滚）、`DemoStateApiTest`（三种条件装配及请求转发）、`ManagedStorageSwapTest`（路径边界与 `.gitkeep` 目录）。
- 验证命令：`/mnt/g/Obsidian/code/01_System_Core/scripts/maven/run-wsl.sh --project /mnt/f/IdeaProjects/hsmap/standardized-products/fact-verification-skill-workbench/.worktrees/feat/001-evaluation-demo-snapshot --log /mnt/f/IdeaProjects/hsmap/.tmp/fact-verification-logs/task4-review-green.log -- -pl backend -am test -Dtest=DemoStateServiceTest,ManagedStorageSwapTest,DemoStateApiTest`。
- 结果：14 项测试（API 4、服务 8、存储 2）均为 failures=0、errors=0，`BUILD SUCCESS`；随后执行 `git diff --check`。
