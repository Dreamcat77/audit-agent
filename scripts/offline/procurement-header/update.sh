#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077
[[ ${EUID:-$(id -u)} == 0 ]] || exec sudo bash "$0" "$@"
install_root=/
package_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
http_base=${1:-http://127.0.0.1}
die() { echo "错误：$*"; exit 1; }
[[ $# -le 1 && $http_base =~ ^http://127\.0\.0\.1(:[0-9]{1,5})?/?$ ]] || die '只接受本机HTTP检查地址'
http_base=${http_base%/}
exec 9>"${install_root%/}/run/audit-agent-offline-install.lock"
flock -n 9 || die '其他安装或更新仍在运行'
[[ -f "${install_root%/}/etc/audit-agent/portable-install.marker" ]] || die '离线安装尚未完成'
(cd "$package_dir" && sha256sum -c manifest.sha256 >/dev/null) || die '补丁完整性校验失败'
[[ -z $(find "$package_dir/variants" -type l -print -quit) ]] || die '补丁含不安全链接'
release=$(readlink -f "${install_root%/}/opt/audit-agent/current")
case "$release" in "${install_root%/}/opt/audit-agent/releases/"*) ;; *) die '应用不属于离线安装目录' ;; esac
dist="$release/frontend/dist"
[[ -d "$dist" && ! -L "$release/frontend" && ! -L "$dist" && -f "$dist/index.html" && ! -L "$dist/index.html" && -z $(find "$dist" -type l -print -quit) ]] || die '前端目录缺失或含链接'
hash=$(sha256sum "$dist/index.html" | awk '{print $1}')
variant=$(awk -v hash="$hash" '$1==hash{print $2}' "$package_dir/entry-map.txt")
[[ $variant =~ ^r[0-9]+$ ]] || die '当前前端不在已验证版本清单中，停止覆盖'
payload="$package_dir/variants/$variant"
(cd "$dist" && sha256sum -c "$payload/original-assets.sha256" >/dev/null) || die '当前资源与原版本不匹配'
nginx -t || die '现有Nginx语法检查失败'
curl -fsS --max-time 10 "$http_base/api/v1/health" >/dev/null || die '原系统健康检查失败'
while IFS= read -r -d '' file; do
  relative=${file#"$payload/dist/"}
  [[ $relative != index.html ]] || continue
  if [[ -e "$dist/$relative" ]]; then cmp -s "$file" "$dist/$relative" || die '静态资源文件名冲突'; fi
done < <(find "$payload/dist" -type f -print0)
install -d -m 0700 "${install_root%/}/opt/audit-agent/frontend-backups"
backup=$(mktemp -d "${install_root%/}/opt/audit-agent/frontend-backups/header-font-20261010.XXXXXX")
cp -a "$dist/index.html" "$backup/index.html"
chmod "$(stat -c %a "$dist/index.html")" "$backup/index.html"
printf '%s\n' "$dist" > "$backup/frontend-path.txt"
(cd "$backup" && sha256sum index.html frontend-path.txt) > "$backup/backup.sha256"
entry_temp=$(mktemp "$dist/.index-header-update.XXXXXX")
applied=0
rollback() {
  if [[ $applied == 1 ]]; then
    cp -a "$backup/index.html" "$entry_temp"
    chmod "$(stat -c %a "$backup/index.html")" "$entry_temp"
    mv -f "$entry_temp" "$dist/index.html"
    if command -v restorecon >/dev/null; then restorecon "$dist/index.html" || true; fi
    applied=0
    echo '检查失败，已恢复原入口；Nginx和后端未修改。'
  fi
  echo "入口备份：$backup"
}
trap 'rollback' ERR
trap '[[ ! -f "$entry_temp" ]] || rm -f "$entry_temp"' EXIT
trap 'rollback; exit 130' INT HUP TERM
group=$(stat -c %g "$dist/index.html")
while IFS= read -r -d '' file; do
  relative=${file#"$payload/dist/"}
  [[ $relative != index.html ]] || continue
  if [[ ! -e "$dist/$relative" ]]; then
    install -d -m 0755 "$(dirname "$dist/$relative")"
    install -m 0644 -o 0 -g "$group" "$file" "$dist/$relative"
  fi
done < <(find "$payload/dist" -type f -print0)
install -m 0644 -o 0 -g "$group" "$payload/dist/index.html" "$entry_temp"
applied=1
mv -f "$entry_temp" "$dist/index.html"
if command -v restorecon >/dev/null; then restorecon -RF "$dist"; fi
check() {
  local actual expected file relative code
  while IFS= read -r -d '' file; do
    relative=${file#"$payload/dist/"}
    [[ $relative != index.html ]] || relative=''
    expected=$(sha256sum "$file" | awk '{print $1}')
    actual=$(curl -fsS --max-time 30 -H 'Cache-Control: no-cache' "$http_base/$relative" | sha256sum | awk '{print $1}') || return 1
    [[ $actual == "$expected" ]] || return 1
  done < <(find "$payload/dist" -type f -print0)
  curl -fsS --max-time 10 "$http_base/api/v1/health" >/dev/null || return 1
  code=$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' "$http_base/api/v1/procurement/projects/source-projects") || return 1
  [[ $code == 401 ]]
}
if ! check; then rollback; die '页面、资源、健康或未登录检查失败'; fi
sha256sum "$dist/index.html" > "$backup/post-update.sha256"
applied=0
trap - ERR
echo '采购表头离线中文字体及HTTP检查通过。请Ctrl+F5刷新。'
echo "入口备份：$backup"
echo '原页面JS/CSS、采购上传行为、Nginx、后端、AI配置和业务数据保留；未重启服务。'
