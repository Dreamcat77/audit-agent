#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077
[[ ${EUID:-$(id -u)} == 0 ]] || exec sudo bash "$0" "$@"
install_root=/
die() { echo "错误：$*"; exit 1; }
[[ $# == 1 ]] || die '用法：sudo bash rollback.sh 更新时输出的入口备份目录'
exec 9>"${install_root%/}/run/audit-agent-offline-install.lock"
flock -n 9 || die '其他安装或更新正在运行'
backup=$(readlink -f "$1")
case "$backup" in "${install_root%/}/opt/audit-agent/frontend-backups/header-font-20261010."*) ;; *) die '不是本次补丁备份目录' ;; esac
[[ ! -L "$1" && -z $(find "$backup" -type l -print -quit) ]] || die '备份含链接'
(cd "$backup" && sha256sum -c backup.sha256 >/dev/null) || die '备份校验失败'
release=$(readlink -f "${install_root%/}/opt/audit-agent/current")
case "$release" in "${install_root%/}/opt/audit-agent/releases/"*) ;; *) die '应用目录不匹配' ;; esac
dist="$release/frontend/dist"
[[ $(<"$backup/frontend-path.txt") == "$dist" && ! -L "$release/frontend" && ! -L "$dist" && ! -L "$dist/index.html" ]] || die '入口不匹配'
sha256sum -c "$backup/post-update.sha256" >/dev/null || die '入口被其他更新修改，停止覆盖'
entry_temp=$(mktemp "$dist/.index-header-rollback.XXXXXX")
trap '[[ ! -f "$entry_temp" ]] || rm -f "$entry_temp"' EXIT
cp -a "$backup/index.html" "$entry_temp"
chmod "$(stat -c %a "$backup/index.html")" "$entry_temp"
chown "$(stat -c %u:%g "$backup/index.html")" "$entry_temp"
mv -f "$entry_temp" "$dist/index.html"
if command -v restorecon >/dev/null; then restorecon "$dist/index.html"; fi
echo '表头补丁已撤销。请Ctrl+F5；原静态资源保留，Nginx/后端/AI/业务数据未修改。'
