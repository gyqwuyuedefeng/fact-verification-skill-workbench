# 影子灰度、晋升与回滚证据

- 同条件评测：589d49fe-f2d9-4e38-8d25-fca6051a6af9
- 原 Stable：fc91c90c-4c10-4fc1-9c9e-1fa5caa9ea40
- Candidate：b9824010-ee1b-4cea-be32-a7e732297a8c
- 影子人工 PASS：1
- 影子人工 FAIL：1
- 晋升后新任务 PRIMARY：663c4dfd-eaf9-4143-9e4b-31b8a5cfbf07
- 回滚后新任务 PRIMARY：ba4bbd11-b1dc-4c94-8134-1b7f76bf3055
- 最终状态：ROLLBACK，Stable 已恢复为 fc91c90c-4c10-4fc1-9c9e-1fa5caa9ea40

发布链路完成后，Agent 运行时仅增加一次“schema 不合法时重新取证并纠正”的稳定性修复，因此旧发布记录不被覆盖。当前运行时已用相同三版本重新评测：`dd376d88-21d2-4566-9e6f-16addf4e29d4`，结果仍为 `GATE PASS`；当前报告与 Run Manifest 以该新评测为准。

完整追加历史及任务标识见同目录 `release-evidence.json`。
