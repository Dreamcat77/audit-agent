#!/usr/bin/env bash
set -Eeuo pipefail
set +x
[[ $# == 1 ]] || { echo '用法：sudo bash apply.sh 原Audit_Update_v10解压目录'; exit 2; }
[[ ${EUID:-$(id -u)} == 0 ]] || exec sudo bash "$0" "$@"
patch_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
exec 9>/run/audit-agent-offline-install.lock
flock -n 9 || { echo 'PATCH_BLOCKED reasonCode=UPDATE_LOCKED'; exit 1; }
cd "$patch_dir"
sha256sum -c manifest.sha256 >/dev/null || { echo 'PATCH_BLOCKED reasonCode=PATCH_HASH'; exit 1; }
exec /opt/python/venvs/audit-ai/bin/python -I "$patch_dir/patch.py" "$1"
