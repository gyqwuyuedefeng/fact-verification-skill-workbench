#!/usr/bin/env bash
# 启动比赛复演所需的 MCP、后端与前端；显式区分 WSL 转发和公司内网直连模式。

set -euo pipefail

usage() {
  printf '用法: bash %s <wsl|intranet> [--print-config]\n' "${0##*/}" >&2
  printf '  wsl       通过 127.0.0.1 上的既有受管转发访问测试依赖\n' >&2
  printf '  intranet  直接使用既有测试配置中的公司内网地址\n' >&2
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage
  exit 2
fi

start_mode="$1"
case "$start_mode" in
  wsl | intranet) ;;
  *)
    usage
    exit 2
    ;;
esac

print_config_only=false
if [[ $# -eq 2 ]]; then
  if [[ "$2" != '--print-config' ]]; then
    usage
    exit 2
  fi
  print_config_only=true
fi

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
project_root="$(cd -- "$script_directory/../.." && pwd -P)"
# worktree 中的项目目录位于主仓库内部，不能再按当前目录的父级推算兄弟项目。
# Git 公共目录始终属于主仓库；只用它定位 FireLM/metastart 配置和统一日志目录，
# 实际启动的代码与构建产物仍来自当前 project_root。
git_common_directory="$(git -C "$project_root" rev-parse --path-format=absolute --git-common-dir)"
primary_project_root="$(cd -- "$(dirname -- "$git_common_directory")" && pwd -P)"
standardized_products_root="$(cd -- "$primary_project_root/.." && pwd -P)"
hsmap_root="$(cd -- "$primary_project_root/../.." && pwd -P)"
log_root="$hsmap_root/.tmp/fact-verification-logs/live-preview-$start_mode"
firelm_env="$standardized_products_root/ai-firelm/backend/.env.staging"
metastart_dev="$standardized_products_root/metastart/src/main/resources/application-dev.yml"

test -f "$firelm_env"
test -f "$metastart_dev"

read_env_value() {
  local variable_name="$1"
  awk -v wanted="$variable_name" 'index($0,wanted "=")==1{print substr($0,length(wanted)+2); exit}' \
    "$firelm_env" | tr -d '\r'
}

configured_db_host="$(read_env_value DB_HOST)"
configured_db_port="$(read_env_value DB_PORT)"
configured_db_name="$(read_env_value DB_NAME)"
app_db_username="$(read_env_value DB_USER)"
app_db_password="$(read_env_value DB_PASSWORD)"
configured_model_url="$(read_env_value LOCAL_MODEL_URL)"
local_model_id="$(read_env_value LOCAL_MODEL_ID)"
local_model_api_key="$(read_env_value LOCAL_MODEL_API_KEY)"
configured_es_addresses="$(awk 'BEGIN{i=0} /^  elasticsearch:/{i=1;next} i&&/^    address:/{sub(/^    address:[[:space:]]*/,""); print; exit}' "$metastart_dev" | tr -d '\r')"
configured_es_schema="$(awk 'BEGIN{i=0} /^  elasticsearch:/{i=1;next} i&&/^    schema:/{sub(/^    schema:[[:space:]]*/,""); print; exit}' "$metastart_dev" | tr -d '\r')"
es_username="$(awk 'BEGIN{i=0} /^  elasticsearch:/{i=1;next} i&&/^    userName:/{sub(/^    userName:[[:space:]]*/,""); print; exit}' "$metastart_dev" | tr -d '\r')"
es_password="$(awk 'BEGIN{i=0} /^  elasticsearch:/{i=1;next} i&&/^    password:/{sub(/^    password:[[:space:]]*/,""); print; exit}' "$metastart_dev" | tr -d '\r')"

test -n "$configured_db_host"
test -n "$configured_db_port"
test -n "$configured_db_name"
test -n "$app_db_username"
test -n "$app_db_password"
test -n "$configured_model_url"
test -n "$local_model_id"
test -n "$configured_es_addresses"
test -n "$configured_es_schema"
test -n "$es_username"
test -n "$es_password"

if [[ "$start_mode" == 'wsl' ]]; then
  db_host='127.0.0.1'
  db_port='45432'
  db_name='kjjr_inx_brain'
  es_addresses='127.0.0.1:29200,127.0.0.1:29201,127.0.0.1:29202'
  es_schema='http'
  model_url='http://127.0.0.1:48080/v1/chat/completions'
else
  db_host="$configured_db_host"
  db_port="$configured_db_port"
  db_name="$configured_db_name"
  es_addresses="$configured_es_addresses"
  es_schema="$configured_es_schema"
  model_url="$configured_model_url"

  [[ "$db_host" != 'localhost' && "$db_host" != 127.* ]] || {
    printf 'intranet 模式拒绝使用回环数据库地址\n' >&2
    exit 2
  }
  [[ "$es_addresses" != *'localhost'* && "$es_addresses" != *'127.0.0.1'* ]] || {
    printf 'intranet 模式拒绝使用回环 ES 地址\n' >&2
    exit 2
  }
  [[ "$model_url" != *'localhost'* && "$model_url" != *'127.0.0.1'* ]] || {
    printf 'intranet 模式拒绝使用回环模型地址\n' >&2
    exit 2
  }
fi

[[ "$db_name" == 'kjjr_inx_brain' ]] || {
  printf '拒绝启动：数据库必须是 kjjr_inx_brain\n' >&2
  exit 2
}

db_endpoint="$db_host:$db_port/$db_name?currentSchema=test"
db_url="jdbc:postgresql://$db_endpoint"

print_effective_config() {
  printf 'START_MODE=%s\n' "$start_mode"
  printf 'DB_ENDPOINT=%s\n' "$db_endpoint"
  printf 'ES_ADDRESSES=%s\n' "$es_addresses"
  printf 'ES_SCHEMA=%s\n' "$es_schema"
  printf 'MODEL_ENDPOINT=%s\n' "$model_url"
  printf 'WORKBENCH_STORAGE_ROOT=%s/data\n' "$primary_project_root"
  printf 'MCP_ENDPOINT=http://127.0.0.1:19091/mcp\n'
  printf 'BACKEND_ENDPOINT=http://127.0.0.1:19090\n'
  printf 'FRONTEND_ENDPOINT=http://127.0.0.1:15173\n'
  printf 'SPRING_PROFILE=test\n'
  printf 'NACOS_REGISTER_ENABLED=false\n'
}

if [[ "$print_config_only" == true ]]; then
  print_effective_config
  exit 0
fi

test -f "$project_root/mcp-server/target/fact-verification-mcp-server.jar"
test -f "$project_root/backend/target/fact-verification-backend.jar"

mkdir -p "$log_root"

cleanup() {
  trap - INT TERM EXIT
  for process_id in ${frontend_pid:-} ${backend_pid:-} ${mcp_pid:-}; do
    test -z "$process_id" || ! kill -0 "$process_id" 2>/dev/null || kill "$process_id" 2>/dev/null || true
  done
  for process_id in ${frontend_pid:-} ${backend_pid:-} ${mcp_pid:-}; do
    test -z "$process_id" || wait "$process_id" 2>/dev/null || true
  done
}
trap cleanup INT TERM EXIT

wait_for_health() {
  local process_id="$1"
  local url="$2"
  for _attempt in $(seq 1 120); do
    curl -fsS "$url" >/dev/null 2>&1 && return 0
    kill -0 "$process_id"
    sleep 0.25
  done
  return 1
}

cd "$project_root"
print_effective_config
env \
  SNAPSHOT_DB_URL="$db_url" \
  SNAPSHOT_DB_USERNAME="$app_db_username" \
  SNAPSHOT_DB_PASSWORD="$app_db_password" \
  ES_ADDRESSES="$es_addresses" \
  ES_SCHEMA="$es_schema" \
  ES_USERNAME="$es_username" \
  ES_PASSWORD="$es_password" \
  /usr/lib/jvm/java-17-openjdk-amd64/bin/java \
  -jar mcp-server/target/fact-verification-mcp-server.jar \
  --spring.profiles.active=test \
  --spring.cloud.nacos.discovery.register-enabled=false \
  --server.port=19091 > "$log_root/mcp.log" 2>&1 &
mcp_pid=$!
wait_for_health "$mcp_pid" 'http://127.0.0.1:19091/actuator/health'

env \
  APP_DB_URL="$db_url" \
  APP_DB_USERNAME="$app_db_username" \
  APP_DB_PASSWORD="$app_db_password" \
  LOCAL_MODEL_URL="$model_url" \
  LOCAL_MODEL_ID="$local_model_id" \
  LOCAL_MODEL_API_KEY="$local_model_api_key" \
  MCP_BASE_URL='http://127.0.0.1:19091' \
  WORKBENCH_STORAGE_ROOT="$primary_project_root/data" \
  /usr/lib/jvm/java-17-openjdk-amd64/bin/java \
  -jar backend/target/fact-verification-backend.jar \
  --spring.profiles.active=test \
  --spring.cloud.nacos.discovery.register-enabled=false \
  --server.port=19090 > "$log_root/backend.log" 2>&1 &
backend_pid=$!
wait_for_health "$backend_pid" 'http://127.0.0.1:19090/actuator/health'

# 用 exec 让后台子 shell 直接替换为 Vite 真实进程。这样 frontend_pid 对应最终监听者，
# Ctrl+C/TERM 的 cleanup 能精确结束前端，而不会只杀掉 npm 外壳后遗留 Node 子进程。
(cd frontend && exec env CHOKIDAR_USEPOLLING=true ./node_modules/.bin/vite --host 127.0.0.1 --port 15173) \
  > "$log_root/frontend.log" 2>&1 &
frontend_pid=$!
wait_for_health "$frontend_pid" 'http://127.0.0.1:15173/'

printf 'PREVIEW_READY start_mode=%s frontend=http://127.0.0.1:15173 backend_pid=%s mcp_pid=%s frontend_pid=%s\n' \
  "$start_mode" "$backend_pid" "$mcp_pid" "$frontend_pid"
wait
