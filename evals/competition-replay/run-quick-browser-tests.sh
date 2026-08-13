#!/usr/bin/env bash
# 在已经出现 PREVIEW_READY 的终端之外运行“马上能测”真实浏览器回归。

set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
project_root="$(cd -- "$script_directory/../.." && pwd -P)"
node_modules_root="${PLAYWRIGHT_NODE_PATH:-/home/gyq/.nvm/versions/node/v20.20.0/lib/node_modules}"

cd "$project_root"
for test_file in \
  evals/competition-replay/playwright/smoke.cjs \
  evals/competition-replay/playwright/smoke-conflict.cjs \
  evals/competition-replay/playwright/smoke-files.cjs; do
  NODE_PATH="$node_modules_root" node "$test_file"
done

node evals/competition-replay/mcp-live-matrix.cjs
printf 'QUICK_SUITE_PASS：对话、01/02/03、冲突、七类附件和六个 Streamable HTTP MCP 工具均通过。\n'
