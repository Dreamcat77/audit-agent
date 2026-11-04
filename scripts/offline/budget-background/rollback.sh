#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077
[[ ${EUID:-$(id -u)} == 0 ]] || exec sudo bash "$0" "$@"
[[ $# == 1 ]] || { echo '用法：sudo bash rollback.sh 更新输出的恢复记录目录'; exit 1; }
backup=$(readlink -f -- "$1")
case "$backup" in /opt/audit-agent/update-backups/budget-background-20261010.*) ;; *) exit 1 ;; esac
exec 9>/run/audit-agent-offline-install.lock
flock -n 9
[[ -d "$backup" && -z $(find "$backup" -type l -print -quit) ]]
(cd "$backup" && sha256sum -c record.sha256 >/dev/null)
IFS= read -r previous < "$backup/previous.txt"
IFS= read -r release < "$backup/release.txt"
case "$previous" in /opt/audit-agent/releases/*) ;; *) exit 1 ;; esac
[[ $(readlink -f /opt/audit-agent/current) == "$release" && -d "$previous/backend" ]]
ln -s "$previous" /opt/audit-agent/current.budget-rollback.$$
mv -Tf /opt/audit-agent/current.budget-rollback.$$ /opt/audit-agent/current
systemctl restart audit-backend
healthy=no
for attempt in $(seq 1 45); do
  if curl -fsS --max-time 3 http://127.0.0.1/api/v1/health >/dev/null 2>&1; then healthy=yes;break;fi
  sleep 2
done
[[ $healthy == yes ]]
echo '已恢复原版本。预算队列表、原材料及进度保留；待处理任务在重新更新后继续。请Ctrl+F5。'
