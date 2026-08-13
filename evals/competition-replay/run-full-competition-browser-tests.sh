#!/usr/bin/env bash
# 在干净发布状态上运行完整比赛链路；真实公司模型评测耗时较长，脚本会持续输出进度。

set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
project_root="$(cd -- "$script_directory/../.." && pwd -P)"
node_modules_root="${PLAYWRIGHT_NODE_PATH:-/home/gyq/.nvm/versions/node/v20.20.0/lib/node_modules}"

cd "$project_root"
for test_file in \
  evals/competition-replay/playwright/skill-lifecycle.cjs \
  evals/competition-replay/playwright/initial-evaluation.cjs \
  evals/competition-replay/playwright/initialize-stable.cjs \
  evals/competition-replay/playwright/create-optimized-candidate.cjs \
  evals/competition-replay/playwright/three-version-evaluation.cjs \
  evals/competition-replay/playwright/release-shadow-promote-rollback.cjs; do
  NODE_PATH="$node_modules_root" node "$test_file"
done

printf 'FULL_COMPETITION_SUITE_PASS：版本、评测、影子、晋升和回滚比赛链路均通过。\n'
