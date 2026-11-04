#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077
[[ ${EUID:-$(id -u)} == 0 ]] || exec sudo bash "$0" "$@"
[[ $# == 1 ]] || { echo '用法：sudo bash rollback.sh 更新时输出的入口备份目录' >&2; exit 1; }
install_root=/
backup=$(readlink -f "$1")
case "$backup" in "${install_root%/}/opt/audit-agent/frontend-backups/font-fix-v3."*) ;; *) echo '错误：不是 FontFix_v3 备份目录' >&2; exit 1 ;; esac
[[ ! -L "$backup" && -f "$backup/index.html" && -f "$backup/frontend-path.txt" ]] || { echo '错误：备份目录无效' >&2; exit 1; }
exec 9>"${install_root%/}/run/audit-agent-offline-install.lock"
flock -n 9 || { echo '错误：其他安装或更新仍在运行' >&2; exit 1; }
(cd "$backup" && sha256sum -c backup.sha256 >/dev/null) || { echo '错误：备份校验失败' >&2; exit 1; }
current=$(readlink -f "${install_root%/}/opt/audit-agent/current")
case "$current" in "${install_root%/}/opt/audit-agent/releases/"*) ;; *) echo '错误：当前应用不属于离线 release 目录' >&2; exit 1 ;; esac
dist="$current/frontend/dist"
[[ $(<"$backup/frontend-path.txt") == "$dist" && ! -L "$dist" && ! -L "$dist/index.html" ]] || { echo '错误：当前入口路径不匹配' >&2; exit 1; }
[[ -f "$backup/post-update.sha256" ]] && (cd "$dist" && sha256sum -c "$backup/post-update.sha256" >/dev/null) || { echo '错误：入口已被其他更新修改，停止回退' >&2; exit 1; }
tmp=$(mktemp "$dist/.index-font-fix-v3-rollback.XXXXXX")
trap '[[ ! -f "$tmp" ]] || rm -f "$tmp"' EXIT
cp -a "$backup/index.html" "$tmp"
mv -f "$tmp" "$dist/index.html"
if command -v restorecon >/dev/null; then restorecon "$dist/index.html" || true; fi
echo 'FONT_FIX_V3_ROLLBACK=PASS；已恢复原入口。新增静态资源保留，未修改后端、AI、数据库或Nginx。'
