#!/usr/bin/env bash
set -Eeuo pipefail
set +x
[[ ${EUID:-$(id -u)} == 0 ]] || exec sudo bash "$0" "$@"
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
lock_update
[[ $# == 1 ]] || die ROLLBACK_ARGUMENTS
record=$(readlink -f -- "$1")
case "$record" in "$base/update-backups/v10."*) ;; *) die ROLLBACK_RECORD_PATH ;; esac
[[ -d "$record" && ! -L "$1" && -f "$record/record.sha256" ]] || die ROLLBACK_RECORD_MISSING
(cd "$record" && sha256sum -c record.sha256 >/dev/null) || die ROLLBACK_RECORD_HASH
load_record_http
previous=$(cat "$record/previous.txt"); release=$(cat "$record/release.txt")
case "$previous" in "$base/releases/"*) ;; *) die ROLLBACK_OLD_RELEASE_PATH ;; esac
case "$release" in "$base/releases/v10."*) ;; *) die ROLLBACK_NEW_RELEASE_PATH ;; esac
[[ -L "$base/current" && $(readlink -f "$base/current") == "$release" && -d "$previous/backend" ]] || die ROLLBACK_CURRENT_CHANGED
sha256sum -c "$record/configs.sha256" >/dev/null || die ROLLBACK_CONFIG_CHANGED
check_managed_dropins
[[ -f "$unit_root/audit-backend.service.d/99-audit-v10.conf" && -f "$unit_root/audit-ai.service.d/99-audit-v10.conf" ]] || die ROLLBACK_DROPIN_MISSING
cmp -s "$unit_root/audit-backend.service.d/99-audit-v10.conf" "$record/backend.conf" || die ROLLBACK_BACKEND_DROPIN_CHANGED
cmp -s "$unit_root/audit-ai.service.d/99-audit-v10.conf" "$record/ai.conf" || die ROLLBACK_AI_DROPIN_CHANGED
systemctl stop audit-backend audit-ai
switch_current "$previous"
restore_dropins
systemctl daemon-reload
systemctl start audit-ai audit-backend
wait_health || die ROLLBACK_HEALTH
echo 'ROLLBACK=PASS 原代码已恢复；已执行的增量数据库结构和业务数据保留。'
