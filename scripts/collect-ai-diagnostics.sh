#!/usr/bin/env bash
set +x
set -Eeuo pipefail
umask 077
package_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
since='1 hour ago'
request_id=''
output_parent=$PWD
while (( $# )); do
  case "$1" in
    --since) since=${2:?缺少时间范围}; shift 2 ;;
    --request-id) request_id=${2:?缺少请求编号}; shift 2 ;;
    --output-parent) output_parent=${2:?缺少输出父目录}; shift 2 ;;
    *) echo '用法：bash scripts/collect-ai-diagnostics.sh [--since "1 hour ago"] [--request-id 编号] [--output-parent 现有目录]'; exit 2 ;;
  esac
done
[[ -d "$output_parent" ]] || { echo '输出父目录不存在'; exit 2; }
diag_python=''
for candidate in /opt/python/venvs/audit-ai/bin/python /usr/bin/python3; do
  if [[ -x "$candidate" ]] && "$candidate" -I -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' 2>/dev/null; then
    diag_python=$candidate; break
  fi
done
[[ -n "$diag_python" ]] || { echo '缺少可执行的 Python 3.8+；没有安装或修改系统环境'; exit 1; }
[[ -f "$package_dir/tools/ai-log-extract.py" ]] || { echo '缺少配套日志过滤器，请保留 scripts 与 tools 目录'; exit 1; }
output=$(mktemp -d "$output_parent/ai-diagnostics.XXXXXXXX")
# Capture only fixed status fields. Never dump units, process environments or raw journals.
systemctl show audit-backend.service audit-ai.service nginx.service \
  -p Id -p ActiveState -p SubState -p Result -p NRestarts -p MemoryCurrent -p TasksCurrent \
  > "$output/service-status.txt"
set +e
journalctl -u audit-backend.service -u audit-ai.service --since "$since" --no-pager -o json 2>/dev/null \
  | "$diag_python" -I "$package_dir/tools/ai-log-extract.py" --output "$output" --request-id "$request_id"
pipe_codes=("${PIPESTATUS[@]}")
set -e
[[ ${pipe_codes[1]} == 0 && (${pipe_codes[0]} == 0 || ${pipe_codes[0]} == 141) ]] || {
  echo "收集失败，保留已生成文件：$output。请用 sudo 运行并核对时间范围。"; exit 1;
}
(cd "$output" && sha256sum pipeline.jsonl summary.json service-status.txt > manifest.sha256)
echo "已生成脱敏日志：$output"
echo '零条日志可能是尚未复现、时间范围不对、无 journal 权限或版本未更新；不代表系统没有问题。'
echo '请提供 summary.json、pipeline.jsonl 和 service-status.txt。此脚本不调用模型、不改配置、不读取业务文件。'
