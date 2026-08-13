#!/usr/bin/env bash
# 统一预览入口的脱敏契约测试：只检查参数解析和地址选择，不启动任何应用或访问数据库。

set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
project_root="$(cd -- "$script_directory/../.." && pwd -P)"
standardized_products_root="$(cd -- "$project_root/.." && pwd -P)"
launcher="$script_directory/start-test-preview.sh"
firelm_env="$standardized_products_root/ai-firelm/backend/.env.staging"
metastart_dev="$standardized_products_root/metastart/src/main/resources/application-dev.yml"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

assert_contains() {
  local actual="$1"
  local expected="$2"
  [[ "$actual" == *"$expected"* ]] || fail "输出缺少：$expected"
}

read_env_value() {
  local variable_name="$1"
  awk -v wanted="$variable_name" 'index($0,wanted "=")==1{print substr($0,length(wanted)+2); exit}' \
    "$firelm_env" | tr -d '\r'
}

# 先做静态能力探针，确保旧脚本不会因为忽略测试参数而意外启动服务。
grep -Fq -- '--print-config' "$launcher" || fail '启动脚本尚未实现 --print-config'
# 前端后台 PID 必须由子 shell 通过 exec 替换成真实 Vite 进程；否则 Ctrl+C 只会结束 npm 外壳并遗留监听端口。
grep -Fq -- 'exec env CHOKIDAR_USEPOLLING=true ./node_modules/.bin/vite' "$launcher" \
  || fail '前端启动尚未把受管 PID 绑定到真实 Vite 进程'

set +e
missing_mode_output="$(bash "$launcher" 2>&1)"
missing_mode_status=$?
unknown_mode_output="$(bash "$launcher" unknown --print-config 2>&1)"
unknown_mode_status=$?
set -e

[[ $missing_mode_status -ne 0 ]] || fail '不传模式时必须失败'
[[ $unknown_mode_status -ne 0 ]] || fail '未知模式必须失败'
assert_contains "$missing_mode_output" 'wsl|intranet'
assert_contains "$unknown_mode_output" 'wsl|intranet'

wsl_output="$(bash "$launcher" wsl --print-config)"
assert_contains "$wsl_output" 'START_MODE=wsl'
assert_contains "$wsl_output" 'DB_ENDPOINT=127.0.0.1:45432/kjjr_inx_brain?currentSchema=test'
assert_contains "$wsl_output" 'ES_ADDRESSES=127.0.0.1:29200,127.0.0.1:29201,127.0.0.1:29202'
assert_contains "$wsl_output" 'MODEL_ENDPOINT=http://127.0.0.1:48080/v1/chat/completions'

expected_db_host="$(read_env_value DB_HOST)"
expected_db_port="$(read_env_value DB_PORT)"
expected_db_name="$(read_env_value DB_NAME)"
expected_model_url="$(read_env_value LOCAL_MODEL_URL)"
expected_es_addresses="$(awk 'BEGIN{i=0} /^  elasticsearch:/{i=1;next} i&&/^    address:/{sub(/^    address:[[:space:]]*/,""); print; exit}' "$metastart_dev" | tr -d '\r')"
expected_es_schema="$(awk 'BEGIN{i=0} /^  elasticsearch:/{i=1;next} i&&/^    schema:/{sub(/^    schema:[[:space:]]*/,""); print; exit}' "$metastart_dev" | tr -d '\r')"

intranet_output="$(bash "$launcher" intranet --print-config)"
assert_contains "$intranet_output" 'START_MODE=intranet'
assert_contains "$intranet_output" "DB_ENDPOINT=$expected_db_host:$expected_db_port/$expected_db_name?currentSchema=test"
assert_contains "$intranet_output" "ES_ADDRESSES=$expected_es_addresses"
assert_contains "$intranet_output" "ES_SCHEMA=$expected_es_schema"
assert_contains "$intranet_output" "MODEL_ENDPOINT=$expected_model_url"

db_password="$(read_env_value DB_PASSWORD)"
es_password="$(awk 'BEGIN{i=0} /^  elasticsearch:/{i=1;next} i&&/^    password:/{sub(/^    password:[[:space:]]*/,""); print; exit}' "$metastart_dev" | tr -d '\r')"
[[ -z "$db_password" || "$intranet_output" != *"$db_password"* ]] || fail '输出泄漏了数据库密码'
[[ -z "$es_password" || "$intranet_output" != *"$es_password"* ]] || fail '输出泄漏了 ES 密码'

printf 'PASS: 统一预览入口双模式与脱敏契约通过\n'
