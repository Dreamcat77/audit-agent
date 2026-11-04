#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077
[[ ${EUID:-$(id -u)} == 0 ]] || exec sudo bash "$0" "$@"
package_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
http_base=${1:-http://127.0.0.1}
die() { echo "错误：$*"; exit 1; }
[[ $# -le 1 && $http_base =~ ^http://127\.0\.0\.1(:[0-9]{1,5})?/?$ ]] || die '只接受本机HTTP检查地址'
http_base=${http_base%/}
base=/opt/audit-agent
exec 9>/run/audit-agent-offline-install.lock
flock -n 9 || die '其他更新仍在运行'
[[ -f /etc/audit-agent/portable-install.marker ]] || die '离线安装尚未完成'
[[ -z $(find "$package_dir" -type l -print -quit) ]] || die '补丁含不安全链接'
(cd "$package_dir" && sha256sum -c manifest.sha256 >/dev/null) || die '补丁完整性校验失败'
previous=$(readlink -f "$base/current")
case "$previous" in "$base/releases/"*) ;; *) die '原应用目录不匹配' ;; esac
[[ -L "$base/current" && -d "$previous/backend" && -d "$previous/frontend/dist" ]] || die '原应用布局不匹配'
[[ -z $(find "$previous/backend" "$previous/frontend" -type l -print -quit) ]] || die '原代码目录含链接'
[[ -f /etc/audit-agent/backend.env && ! -L /etc/audit-agent/backend.env ]] || die '后端环境文件缺失或含链接'
java=/opt/java/current/bin/java
jar=/opt/java/current/bin/jar
[[ -x "$java" && -x "$jar" ]] || die '原JDK17不可用'
systemctl is-active --quiet audit-backend || die '原后端未运行'
curl -fsS --max-time 10 "$http_base/api/v1/health" >/dev/null || die '原系统健康检查失败'
install -d -m 0700 "$base/update-backups"
backup=$(mktemp -d "$base/update-backups/budget-background-20261010.XXXXXX")
printf '%s\n' "$previous" > "$backup/previous.txt"
mapfile -t configs < <(find /etc/audit-agent /etc/nginx -type f -print | sort)
sha256sum "${configs[@]}" > "$backup/configs.sha256"
set -a
source /etc/audit-agent/backend.env
set +a
[[ -n ${AUDIT_DB_URL:-} && -n ${AUDIT_DB_USERNAME:-} && -n ${AUDIT_DB_PASSWORD:-} ]] || die '数据库环境变量未配置'
driver=$("$jar" tf "$package_dir/payload/backend/audit-backend-0.1.0-SNAPSHOT.jar" | awk '/^BOOT-INF\/lib\/DmJdbcDriver[^/]*\.jar$/ {print}')
[[ $driver =~ ^BOOT-INF/lib/DmJdbcDriver[A-Za-z0-9._-]+\.jar$ ]] || die 'JDBC依赖缺失或不唯一'
(cd "$backup" && "$jar" xf "$package_dir/payload/backend/audit-backend-0.1.0-SNAPSHOT.jar" "$driver")
classpath="$package_dir/tools:$backup/$driver"
sql="$package_dir/tools/V051__budget_material_background.sql"
"$java" -cp "$classpath" BudgetMaterialMigration check "$sql"
"$java" -cp "$classpath" MaterialAsyncMigration check "$backup/async.txt"
release=$(mktemp -d "$base/releases/budget-background-20261010.XXXXXX")
chmod 0755 "$release"
cp -a "$previous/." "$release/"
cp -a "$package_dir/payload/." "$release/"
find "$release/frontend" -type d -exec chmod 0755 {} +
find "$release/frontend" -type f -exec chmod 0644 {} +
"$java" -cp "$classpath" BudgetMaterialMigration apply "$sql"
"$java" -cp "$classpath" BudgetMaterialMigration verify "$sql"
unset AUDIT_DB_PASSWORD
printf '%s\n' "$release" > "$backup/release.txt"
(cd "$backup" && sha256sum previous.txt release.txt configs.sha256) > "$backup/record.sha256"
switched=no
restore() {
  if [[ $switched == yes ]]; then
    ln -s "$previous" "$base/current.budget-restore.$$"
    mv -Tf "$base/current.budget-restore.$$" "$base/current"
    systemctl restart audit-backend || true
    switched=no
    echo '已恢复原代码；预算队列表、材料及进度保留，可重新更新继续处理。'
  fi
  echo "恢复记录：$backup"
}
trap 'code=$?; if [[ $code != 0 ]]; then restore; fi' EXIT
trap 'exit 130' INT HUP TERM
if command -v restorecon >/dev/null; then restorecon -RF "$release"; fi
ln -s "$release" "$base/current.budget-next.$$"
mv -Tf "$base/current.budget-next.$$" "$base/current"
switched=yes
systemctl restart audit-backend
healthy=no
for attempt in $(seq 1 45); do
  if curl -fsS --max-time 3 "$http_base/api/v1/health" >/dev/null 2>&1; then healthy=yes; break; fi
  sleep 2
done
[[ $healthy == yes ]] || die '新后端健康检查失败'
sha256sum -c "$backup/configs.sha256" >/dev/null || die '环境配置校验不一致'
while IFS= read -r -d '' file; do
  relative=${file#"$package_dir/payload/frontend/dist/"}
  [[ $relative != index.html ]] || relative=''
  [[ $(curl -fsS --max-time 30 -H 'Cache-Control: no-cache' "$http_base/$relative" | sha256sum | awk '{print $1}') == $(sha256sum "$file" | awk '{print $1}') ]] || die '前端资源HTTP校验失败'
done < <(find "$package_dir/payload/frontend/dist" -type f -print0)
[[ $(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' "$http_base/api/v1/budget/material-uploads/operations/00000000-0000-0000-0000-000000000000") == 401 ]] || die '预算接口认证检查失败'
trap - EXIT INT HUP TERM
echo '预算后台批量处理更新通过，请Ctrl+F5刷新。'
echo "恢复记录：$backup"
echo '环境配置、AI地址/密钥/模型名称、Nginx及原业务材料保留。仅重启Java后端。'
