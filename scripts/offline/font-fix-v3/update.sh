#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077
[[ ${EUID:-$(id -u)} == 0 ]] || exec sudo bash "$0" "$@"

install_root=/
package_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
check_only=no
if [[ ${1:-} == --check ]]; then check_only=yes; shift; fi
http_base=${1:-http://127.0.0.1}
http_host=${AUDIT_FONT_HTTP_HOST:-${http_base#http://}}
http_host=${http_host%/}
[[ $http_base =~ ^http://127\.0\.0\.1(:[0-9]{1,5})?/?$ ]] || { echo '只接受本机HTTP检查地址'; exit 1; }
[[ $http_host =~ ^[A-Za-z0-9.-]+(:[0-9]{1,5})?$ ]] || { echo 'HTTP Host 无效'; exit 1; }
http_base=${http_base%/}

die() { echo "错误：$*" >&2; exit 1; }
http_get() { curl --noproxy '*' -H "Host: $http_host" -H 'Accept-Encoding: identity' -H 'Cache-Control: no-cache' "$@"; }

exec 9>"${install_root%/}/run/audit-agent-offline-install.lock"
flock -n 9 || die '其他安装或更新仍在运行'
(cd "$package_dir" && sha256sum -c manifest.sha256 >/dev/null) || die '更新包完整性校验失败'

current=$(readlink -f "${install_root%/}/opt/audit-agent/current")
case "$current" in "${install_root%/}/opt/audit-agent/releases/"*) ;; *) die '当前应用不属于离线 release 目录' ;; esac
dist="$current/frontend/dist"
[[ -d "$dist" && ! -L "$current/frontend" && ! -L "$dist" && -f "$dist/index.html" ]] || die '当前前端目录缺失或含链接'
[[ -z $(find "$dist" -type l -print -quit) ]] || die '当前前端含不安全链接'
[[ -z $(find "$package_dir/payload" -type l -print -quit) ]] || die '更新包含不安全链接'

expected=$(awk '$2=="index.html"{print $1; exit}' "$package_dir/entry-map.txt")
actual=$(sha256sum "$dist/index.html" | awk '{print $1}')
expected_new=$(sha256sum "$package_dir/payload/index.html" | awk '{print $1}')
if [[ "$actual" == "$expected_new" ]]; then
  while IFS= read -r -d '' file; do
    relative=${file#"$package_dir/payload/assets/"}
    cmp -s "$file" "$dist/assets/$relative" || die "已更新页面的资源不完整：assets/$relative"
    http_hash=$(http_get -fsS --max-time 30 "$http_base/assets/$relative" | sha256sum | awk '{print $1}')
    [[ "$http_hash" == "$(sha256sum "$file" | awk '{print $1}')" ]] || die "已更新页面的HTTP资源不匹配：assets/$relative"
  done < <(find "$package_dir/payload/assets" -type f -print0)
  http_hash=$(http_get -fsS --max-time 30 "$http_base/" | sha256sum | awk '{print $1}')
  [[ "$http_hash" == "$expected_new" ]] || die '已更新入口HTTP校验失败'
  http_get -fsS --max-time 10 "$http_base/api/v1/health" >/dev/null || die '系统健康检查失败'
  echo 'FONT_FIX_V3=ALREADY_APPLIED；未修改文件、未重启服务。'
  exit 0
fi
[[ -n "$expected" && "$actual" == "$expected" ]] || die "当前入口版本不匹配 expected=$expected actual=$actual"
(cd "$dist" && sha256sum -c "$package_dir/original-assets.sha256" >/dev/null) || die '当前前端静态资源与目标版本不匹配'

nginx -t >/dev/null || die '现有 Nginx 配置检查失败'
http_get -fsS --max-time 10 "$http_base/api/v1/health" >/dev/null || die '原系统健康检查失败'
http_actual=$(http_get -fsS --max-time 30 "$http_base/" | sha256sum | awk '{print $1}')
[[ "$http_actual" == "$actual" ]] || die 'HTTP入口与current目录不一致，请核对Nginx root或缓存'
while read -r expected_hash relative; do
  http_hash=$(http_get -fsS --max-time 30 "$http_base/$relative" | sha256sum | awk '{print $1}')
  [[ "$http_hash" == "$expected_hash" ]] || die "原静态资源HTTP校验失败：$relative"
done < "$package_dir/original-assets.sha256"

while IFS= read -r -d '' file; do
  relative=${file#"$package_dir/payload/assets/"}
  if [[ -e "$dist/assets/$relative" ]] && ! cmp -s "$file" "$dist/assets/$relative"; then
    die "静态资源文件名冲突：assets/$relative"
  fi
done < <(find "$package_dir/payload/assets" -type f -print0)

if [[ $check_only == yes ]]; then
  echo 'FONT_FIX_V3_CHECK=PASS；未修改文件、未重启服务。'
  exit 0
fi

backup_root="${install_root%/}/opt/audit-agent/frontend-backups"
install -d -m 0700 "$backup_root"
backup=$(mktemp -d "$backup_root/font-fix-v3.XXXXXX")
cp -a "$dist/index.html" "$backup/index.html"
printf '%s\n' "$dist" > "$backup/frontend-path.txt"
(cd "$backup" && sha256sum index.html frontend-path.txt) > "$backup/backup.sha256"
entry_temp=$(mktemp "$dist/.index-font-fix-v3.XXXXXX")
applied=no

rollback() {
  if [[ $applied == yes ]]; then
    cp -a "$backup/index.html" "$entry_temp"
    mv -f "$entry_temp" "$dist/index.html"
    applied=no
    echo '校验失败，已恢复原入口；新增静态资源保留，后端/AI/数据库/Nginx未修改。' >&2
  fi
  echo "入口备份：$backup" >&2
}
die() {
  echo "错误：$*" >&2
  if [[ ${applied:-no} == yes ]]; then rollback; fi
  exit 1
}

trap rollback ERR
trap '[[ ! -f "$entry_temp" ]] || rm -f "$entry_temp"' EXIT
trap 'rollback; exit 130' INT HUP TERM

group=$(stat -c %g "$dist/index.html")
while IFS= read -r -d '' file; do
  relative=${file#"$package_dir/payload/assets/"}
  if [[ ! -e "$dist/assets/$relative" ]]; then
    install -d -m 0755 "$dist/assets"
    install -m 0644 -o 0 -g "$group" "$file" "$dist/assets/$relative"
  fi
done < <(find "$package_dir/payload/assets" -type f -print0)

install -m 0644 -o 0 -g "$group" "$package_dir/payload/index.html" "$entry_temp"
applied=yes
mv -f "$entry_temp" "$dist/index.html"
if command -v restorecon >/dev/null; then restorecon -RF "$dist" || true; fi

new_hash=$(sha256sum "$dist/index.html" | awk '{print $1}')
[[ "$new_hash" == "$expected_new" ]] || die '新入口落盘校验失败'
http_actual=$(http_get -fsS --max-time 30 -H 'Cache-Control: no-cache' "$http_base/" | sha256sum | awk '{print $1}')
[[ "$http_actual" == "$new_hash" ]] || die '新入口HTTP校验失败'
while IFS= read -r -d '' file; do
  relative=${file#"$package_dir/payload/assets/"}
  expected_file=$(sha256sum "$file" | awk '{print $1}')
  actual_file=$(http_get -fsS --max-time 30 -H 'Cache-Control: no-cache' "$http_base/assets/$relative" | sha256sum | awk '{print $1}')
  [[ "$actual_file" == "$expected_file" ]] || die "新静态资源HTTP校验失败：assets/$relative"
done < <(find "$package_dir/payload/assets" -type f -print0)
http_get -fsS --max-time 10 "$http_base/api/v1/health" >/dev/null || die '更新后系统健康检查失败'

sha256sum "$dist/index.html" > "$backup/post-update.sha256"
applied=no
trap - ERR
echo 'FONT_FIX_V3=PASS；采购类型列本地中文字体已更新。请浏览器 Ctrl+F5，无需重启服务。'
echo "入口备份：$backup"
