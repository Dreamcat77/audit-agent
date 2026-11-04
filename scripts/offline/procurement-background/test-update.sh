#!/usr/bin/env bash
set -Eeuo pipefail
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
work=$(mktemp -d /tmp/audit-background-regression.XXXXXX)
[[ $work == /tmp/audit-background-regression.* ]] || exit 1
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/bin"
cat > "$work/bin/systemctl" <<'STUB'
#!/usr/bin/env bash
if [[ ${AUDIT_TEST_FAIL:-} == restart && $1 == restart ]]; then exit 99; fi
exit 0
STUB
cat > "$work/bin/curl" <<'STUB'
#!/usr/bin/env bash
url=${!#}
case "$url" in
  */api/v1/health|*/health) printf '{"code":200}\n' ;;
  */api/v1/procurement/materials) printf 401 ;;
  */)
    if [[ ${AUDIT_TEST_FAIL:-} == index ]]; then printf 'WRONG ENTRY'; else cat "$AUDIT_TEST_BASE/current/frontend/dist/index.html"; fi ;;
  *) exit 22 ;;
esac
STUB
printf '#!/bin/sh\nexit 0\n' > "$work/bin/restorecon"
printf '#!/bin/sh\nexit 0\n' > "$work/bin/sleep"
chmod 0755 "$work/bin/"*
export PATH="$work/bin:$PATH"
make_case() {
  case_dir="$work/$1"
  root="$case_dir/system"
  package="$case_dir/package"
  base="$root/opt/audit-agent"
  previous="$base/releases/baseline"
  mkdir -p "$previous/backend" "$previous/frontend/dist/assets" "$previous/services/audit-ai/src/audit_ai" \
    "$root/etc/audit-agent" "$root/etc/nginx" "$root/run" "$root/opt/java/current/bin" \
    "$root/opt/python/venvs/audit-ai/bin" "$package/payload/backend" "$package/payload/frontend/dist" \
    "$package/payload/services/audit-ai/src/audit_ai" "$package/tools"
  printf 'OLD ENTRY\n' > "$previous/frontend/dist/index.html"
  printf 'OLD RESOURCE\n' > "$previous/frontend/dist/assets/old.js"
  printf 'OLD BACKEND\n' > "$previous/backend/audit-backend-0.1.0-SNAPSHOT.jar"
  printf 'OLD AI\n' > "$previous/services/audit-ai/src/audit_ai/material_fields.py"
  printf 'NEW ENTRY\n' > "$package/payload/frontend/dist/index.html"
  printf 'NEW BACKEND\n' > "$package/payload/backend/audit-backend-0.1.0-SNAPSHOT.jar"
  printf 'value = 1\n' > "$package/payload/services/audit-ai/src/audit_ai/material_fields.py"
  printf 'client_max_body_size 502m;\n' > "$root/etc/nginx/nginx.conf"
  printf 'MODEL_CONFIG_FIXTURE\n' > "$root/etc/audit-agent/audit-ai.env"
  printf 'AUDIT_DB_URL=fixture\nAUDIT_DB_USERNAME=fixture\nAUDIT_DB_PASSWORD=fixture\n' > "$root/etc/audit-agent/backend.env"
  touch "$root/etc/audit-agent/portable-install.marker"
  ln -s "$previous" "$base/current"
  cat > "$root/opt/java/current/bin/java" <<'STUB'
#!/usr/bin/env bash
if [[ ${AUDIT_TEST_FAIL:-} == schema && $4 == apply ]]; then exit 8; fi
echo 'SCHEMA_FIXTURE=PASS'
STUB
  cat > "$root/opt/java/current/bin/jar" <<'STUB'
#!/usr/bin/env bash
if [[ $1 == tf ]]; then echo BOOT-INF/lib/DmJdbcDriver18-fixture.jar; else mkdir -p BOOT-INF/lib; touch BOOT-INF/lib/DmJdbcDriver18-fixture.jar; fi
STUB
  printf '#!/bin/sh\nexit 0\n' > "$root/opt/python/venvs/audit-ai/bin/python"
  chmod 0755 "$root/opt/java/current/bin/"* "$root/opt/python/venvs/audit-ai/bin/python"
  for script in update.sh rollback.sh; do
    sed -e 's/\[\[ ${EUID:-$(id -u)} == 0 \]\] || exec sudo bash "$0" "$@"/true/' \
      -e "s@^install_root=/\$@install_root=$root@" "$source_dir/$script" > "$package/$script"
  done
  (cd "$package" && find . -type f -print0 | sort -z | xargs -0 sha256sum) > "$case_dir/manifest"
  cp "$case_dir/manifest" "$package/manifest.sha256"
  sha256sum "$root/etc/nginx/nginx.conf" "$root/etc/audit-agent/"*.env "$previous/backend/"*.jar \
    "$previous/services/audit-ai/src/audit_ai/material_fields.py" > "$case_dir/protected.sha256"
  export AUDIT_TEST_BASE="$base"
  unset AUDIT_TEST_FAIL
}
run_update() { bash "$package/update.sh" > "$case_dir/update.log" 2>&1; }
assert_original() {
  [[ $(readlink -f "$base/current") == "$previous" ]]
  sha256sum -c "$case_dir/protected.sha256" >/dev/null
}
make_case success
run_update || { cat "$case_dir/update.log"; exit 1; }
new_release=$(readlink -f "$base/current")
[[ $new_release != "$previous" && $(stat -c %a "$new_release") == 755 ]]
cmp "$new_release/frontend/dist/index.html" "$package/payload/frontend/dist/index.html"
grep -Fxq 'OLD RESOURCE' "$new_release/frontend/dist/assets/old.js"
sha256sum -c "$case_dir/protected.sha256" >/dev/null
backup=$(find "$base/update-backups" -mindepth 1 -maxdepth 1 -type d)
bash "$package/rollback.sh" "$backup" > "$case_dir/rollback.log" 2>&1 || { cat "$case_dir/rollback.log"; exit 1; }
assert_original
for failure in restart index schema; do
  make_case "$failure"
  export AUDIT_TEST_FAIL="$failure"
  if run_update; then cat "$case_dir/update.log"; exit 1; fi
  assert_original
done
make_case tamper
printf 'TAMPER\n' >> "$package/payload/frontend/dist/index.html"
if run_update; then exit 1; fi
assert_original
make_case unsafe_link
ln -s "$previous/backend/audit-backend-0.1.0-SNAPSHOT.jar" "$package/payload/backend/unsafe.jar"
if run_update; then exit 1; fi
assert_original
make_case changed_config
run_update || { cat "$case_dir/update.log"; exit 1; }
new_release=$(readlink -f "$base/current")
backup=$(find "$base/update-backups" -mindepth 1 -maxdepth 1 -type d)
printf 'USER CONFIG CHANGE\n' >> "$root/etc/nginx/nginx.conf"
if bash "$package/rollback.sh" "$backup" > "$case_dir/rollback.log" 2>&1; then exit 1; fi
[[ $(readlink -f "$base/current") == "$new_release" ]]
make_case subsequent_release
run_update || { cat "$case_dir/update.log"; exit 1; }
backup=$(find "$base/update-backups" -mindepth 1 -maxdepth 1 -type d)
mkdir "$base/releases/subsequent"
ln -s "$base/releases/subsequent" "$base/next"
mv -Tf "$base/next" "$base/current"
if bash "$package/rollback.sh" "$backup" > "$case_dir/rollback.log" 2>&1; then exit 1; fi
[[ $(readlink -f "$base/current") == "$base/releases/subsequent" ]]
echo 'BACKGROUND_UPDATE_SUCCESS_ROLLBACK_HTTP_SERVICE_SCHEMA_FAILURE_TAMPER_LINK_CONFIG_LATER_RELEASE=PASS'
