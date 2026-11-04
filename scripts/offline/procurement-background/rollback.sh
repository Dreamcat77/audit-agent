#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077
[[ ${EUID:-$(id -u)} == 0 ]] || exec sudo bash "$0" "$@"
install_root=/
base="${install_root%/}/opt/audit-agent"
die() { echo "错误：$*"; exit 1; }
[[ $# == 1 ]] || die '用法：sudo bash rollback.sh 更新时输出的恢复记录目录'
exec 9>"${install_root%/}/run/audit-agent-offline-install.lock"
flock -n 9 || die '其他安装或更新正在运行'
backup=$(readlink -f "$1")
case "$backup" in "$base/update-backups/material-background-20261010."*) ;; *) die '恢复目录不匹配' ;; esac
[[ ! -L "$1" && -z $(find "$backup" -type l -print -quit) ]] || die '恢复记录含链接'
(cd "$backup" && sha256sum -c record.sha256 >/dev/null) || die '恢复记录校验失败'
previous=$(<"$backup/previous.txt")
release=$(<"$backup/release.txt")
case "$previous" in "$base/releases/"*) ;; *) die '原版本不在应用目录' ;; esac
case "$release" in "$base/releases/material-background-20261010."*) ;; *) die '新版本记录不匹配' ;; esac
[[ -L "$base/current" && $(readlink -f "$base/current") == "$release" && -d "$previous" ]] || die '已有后续更新，停止覆盖'
sha256sum -c "$backup/configs.sha256" >/dev/null || die '配置已变化，停止自动恢复'
ln -s "$previous" "$base/current.material-rollback.$$"
mv -Tf "$base/current.material-rollback.$$" "$base/current"
systemctl restart audit-ai audit-backend
healthy=no
for attempt in $(seq 1 45); do
  if curl -fsS --max-time 3 http://127.0.0.1:8080/api/v1/health >/dev/null 2>&1 && curl -fsS --max-time 3 http://127.0.0.1:8001/health >/dev/null 2>&1; then healthy=yes; break; fi
  sleep 2
done
[[ $healthy == yes ]] || die '原代码已切回，但服务健康未通过；请保留恢复目录排查'
sha256sum -c "$backup/configs.sha256" >/dev/null
echo '原代码已恢复；数据库扩展约束、材料及任务记录保留。'
echo '原版本不会消费QUEUED任务，需要恢复新版后继续解析；不要删除排队记录。'
