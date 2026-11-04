#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077
[[ ${EUID:-$(id -u)} == 0 ]] || exec sudo bash "$0" "$@"
install_root=/
package_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
http_base=${1:-http://127.0.0.1}
die() { echo "错误：$*"; exit 1; }
[[ $# -le 1 && $http_base =~ ^http://127\.0\.0\.1(:[0-9]{1,5})?/?$ ]] || die '只接受本机HTTP检查地址，例如 http://127.0.0.1:80'
http_base=${http_base%/}
base="${install_root%/}/opt/audit-agent"
etc="${install_root%/}/etc/audit-agent"
exec 9>"${install_root%/}/run/audit-agent-offline-install.lock"
flock -n 9 || die '其他安装或更新正在运行'
[[ -f "$etc/portable-install.marker" ]] || die '离线安装尚未完成'
[[ -z $(find "$package_dir" -type l -print -quit) ]] || die '补丁含链接，停止更新'
(cd "$package_dir" && sha256sum -c manifest.sha256 >/dev/null) || die '补丁完整性校验失败'
previous=$(readlink -f "$base/current")
case "$previous" in "$base/releases/"*) ;; *) die '应用不属于离线安装目录' ;; esac
[[ -L "$base/current" && -d "$previous/backend" && -d "$previous/frontend/dist" && -d "$previous/services/audit-ai/src/audit_ai" ]] || die '应用布局不匹配'
[[ -z $(find "$previous/backend" "$previous/frontend" "$previous/services/audit-ai/src" -type l -print -quit) ]] || die '待更新代码目录含链接'
[[ -f "$etc/backend.env" && ! -L "$etc/backend.env" ]] || die '后端环境文件缺失或含链接'
java="${install_root%/}/opt/java/current/bin/java"
jar="${install_root%/}/opt/java/current/bin/jar"
python="${install_root%/}/opt/python/venvs/audit-ai/bin/python"
[[ -x "$java" && -x "$jar" && -x "$python" ]] || die '原JDK17或Python运行环境不可用'
systemctl is-active --quiet audit-backend && systemctl is-active --quiet audit-ai || die '原后端或AI服务未运行'
curl -fsS --max-time 10 "$http_base/api/v1/health" >/dev/null || die '原系统健康检查失败'
install -d -m 0700 "$base/update-backups"
backup=$(mktemp -d "$base/update-backups/material-background-20261010.XXXXXX")
printf '%s\n' "$previous" > "$backup/previous.txt"
mapfile -t configs < <(find "$etc" "${install_root%/}/etc/nginx" -type f -print | sort)
[[ ${#configs[@]} -gt 0 ]] || die '环境配置核对失败'
sha256sum "${configs[@]}" > "$backup/configs.sha256"
# Never print environment variables or model credentials.
set -a
source "$etc/backend.env"
set +a
[[ -n ${AUDIT_DB_URL:-} && -n ${AUDIT_DB_USERNAME:-} && -n ${AUDIT_DB_PASSWORD:-} ]] || die '数据库连接环境变量未配置'
driver_entry=$("$jar" tf "$package_dir/payload/backend/audit-backend-0.1.0-SNAPSHOT.jar" | awk '/^BOOT-INF\/lib\/DmJdbcDriver[^/]*\.jar$/ {print}')
[[ $driver_entry =~ ^BOOT-INF/lib/DmJdbcDriver[A-Za-z0-9._-]+\.jar$ ]] || die 'JDBC运行依赖不唯一或缺失'
(cd "$backup" && "$jar" xf "$package_dir/payload/backend/audit-backend-0.1.0-SNAPSHOT.jar" "$driver_entry")
classpath="$package_dir/tools:$backup/$driver_entry"
"$java" -cp "$classpath" MaterialOrderMigration check "$backup/order-before.txt"
"$java" -cp "$classpath" MaterialAsyncMigration check "$backup/async-before.txt"
release=$(mktemp -d "$base/releases/material-background-20261010.XXXXXX")
chmod 0755 "$release"
cp -a "$previous/." "$release/"
cp -a "$package_dir/payload/." "$release/"
find "$release/frontend" -type d -exec chmod 0755 {} +
find "$release/frontend" -type f -exec chmod 0644 {} +
"$python" - "$release/services/audit-ai/src" <<'PY'
import ast, pathlib, sys
for file in pathlib.Path(sys.argv[1]).rglob('*.py'):
    ast.parse(file.read_text(encoding='utf-8'), filename=file.name)
print('AI_SOURCE_SYNTAX=PASS')
PY
switched=no
restore() {
  if [[ $switched == yes ]]; then
    ln -s "$previous" "$base/current.material-restore.$$"
    mv -Tf "$base/current.material-restore.$$" "$base/current"
    systemctl restart audit-ai audit-backend || true
    switched=no
    echo '更新检查失败，已恢复原代码；业务文件和扩展后的数据库约束保留。'
  fi
  echo "恢复记录：$backup"
}
trap restore ERR
trap 'exit_status=$?; if [[ $exit_status != 0 && $switched == yes ]]; then restore; fi' EXIT
trap 'restore; exit 130' INT HUP TERM
"$java" -cp "$classpath" MaterialOrderMigration apply "$backup/order-before.txt"
"$java" -cp "$classpath" MaterialAsyncMigration apply "$backup/async-before.txt"
"$java" -cp "$classpath" MaterialOrderMigration verify "$backup/order-before.txt"
"$java" -cp "$classpath" MaterialAsyncMigration verify "$backup/async-before.txt"
unset AUDIT_DB_PASSWORD
printf '%s\n' "$release" > "$backup/release.txt"
(cd "$backup" && sha256sum previous.txt release.txt configs.sha256) > "$backup/record.sha256"
if command -v restorecon >/dev/null; then restorecon -RF "$release"; fi
ln -s "$release" "$base/current.material-next.$$"
mv -Tf "$base/current.material-next.$$" "$base/current"
switched=yes
systemctl restart audit-ai audit-backend
healthy=no
for attempt in $(seq 1 45); do
  if curl -fsS --max-time 3 "$http_base/api/v1/health" >/dev/null 2>&1 && curl -fsS --max-time 3 http://127.0.0.1:8001/health >/dev/null 2>&1; then healthy=yes; break; fi
  sleep 2
done
[[ $healthy == yes ]] || die '新后端未通过健康检查'
systemctl is-active --quiet audit-ai && systemctl is-active --quiet audit-backend
sha256sum -c "$backup/configs.sha256" >/dev/null
expected=$(sha256sum "$release/frontend/dist/index.html" | awk '{print $1}')
actual=$(curl -fsS --max-time 10 -H 'Cache-Control: no-cache' "$http_base/" | sha256sum | awk '{print $1}')
[[ $actual == "$expected" ]] || die 'HTTP入口没有提供新前端'
code=$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' "$http_base/api/v1/procurement/materials")
[[ $code == 401 ]] || die '采购材料接口认证检查失败'
trap - ERR INT HUP TERM
echo '采购后台批量解析更新和HTTP检查通过，请在客户端按 Ctrl+F5。'
echo '关闭弹窗、切换页面不影响任务；全部文件上传后才能关闭浏览器。'
echo "恢复记录：$backup"
echo 'AI地址/密钥/模型名称和Nginx配置未修改；数据库仅扩大材料状态及顺序约束。'
