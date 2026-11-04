#!/usr/bin/env bash
set -Eeuo pipefail
set +x
[[ ${EUID:-$(id -u)} == 0 ]] || exec sudo bash "$0" "$@"
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
python=/opt/python/venvs/audit-ai/bin/python
[[ -x $python ]] || { echo 'DIAG_STOP=EXISTING_AI_PYTHON_NOT_FOUND'; exit 1; }
exec "$python" -I "$root/collect.py" "$@"
