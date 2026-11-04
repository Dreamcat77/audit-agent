#!/usr/bin/env bash
set -Eeuo pipefail
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
work=$(mktemp -d /tmp/audit-header-regression.XXXXXX)
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/bin"
printf '#!/bin/sh\nexit 0\n' > "$work/bin/nginx"
printf '#!/bin/sh\nexit 0\n' > "$work/bin/restorecon"
printf '#!/bin/sh\nexit 99\n' > "$work/bin/systemctl"
cat > "$work/bin/curl" <<'STUB'
#!/usr/bin/env bash
url=${!#}
case "$url" in
  */api/v1/health) printf '{"code":200}\n' ;;
  */api/v1/procurement/projects/source-projects) printf 401 ;;
  */assets/*)
    [[ ${AUDIT_TEST_FAIL:-} != assets ]] || exit 22
    cat "$AUDIT_TEST_DIST/assets/${url##*/}" ;;
  */) cat "$AUDIT_TEST_DIST/index.html" ;;
  *) exit 22 ;;
esac
STUB
chmod 755 "$work/bin/"*
export PATH="$work/bin:$PATH"
make_case() {
  case_dir="$work/$1"
  variant=$2
  root="$case_dir/system"
  package="$case_dir/package"
  release="$root/opt/audit-agent/releases/test"
  dist="$release/frontend/dist"
  mkdir -p "$dist/assets" "$release/backend" "$root/etc/nginx" "$root/etc/audit-agent" "$root/run" "$package/variants/$variant/dist/assets"
  printf 'OLD %s ENTRY\n' "$variant" > "$dist/index.html"
  printf 'OLD ASSET\n' > "$dist/assets/old.js"
  printf 'NEW %s ENTRY\n' "$variant" > "$package/variants/$variant/dist/index.html"
  cp "$dist/assets/old.js" "$package/variants/$variant/dist/assets/old.js"
  printf 'NEW HEADER\n' > "$package/variants/$variant/dist/assets/new.js"
  printf 'UNCHANGED BACKEND\n' > "$release/backend/backend.jar"
  printf 'CUSTOM NGINX client_max_body_size 502m;\n' > "$root/etc/nginx/nginx.conf"
  printf 'AI ENDPOINT KEY MODEL FIXTURE\n' > "$root/etc/audit-agent/audit-ai.env"
  chmod 600 "$root/etc/audit-agent/audit-ai.env"
  touch "$root/etc/audit-agent/portable-install.marker"
  ln -s "$release" "$root/opt/audit-agent/current"
  (cd "$dist" && sha256sum assets/old.js) > "$package/variants/$variant/original-assets.sha256"
  (sha256sum "$dist/index.html"; sha256sum "$package/variants/$variant/dist/index.html") | awk -v v="$variant" '{print $1" "v}' > "$package/entry-map.txt"
  for spec in update.sh:update.sh rollback.sh:rollback.sh; do
    sed -e 's/\[\[ ${EUID:-$(id -u)} == 0 \]\] || exec sudo bash "$0" "$@"/true/' \
      -e "s@^install_root=/\$@install_root=$root@" "$source_dir/${spec%%:*}" > "$package/${spec##*:}"
  done
  (cd "$package" && find . -type f -print0 | sort -z | xargs -0 sha256sum) > "$case_dir/manifest"
  cp "$case_dir/manifest" "$package/manifest.sha256"
  sha256sum "$root/etc/nginx/nginx.conf" "$root/etc/audit-agent/audit-ai.env" "$release/backend/backend.jar" > "$case_dir/protected.sha256"
  export AUDIT_TEST_DIST="$dist"
  unset AUDIT_TEST_FAIL
}
run_update() { bash "$package/update.sh" > "$case_dir/output.log" 2>&1; }
for variant in r5 r2; do
  make_case "success_$variant" "$variant"
  run_update || { cat "$case_dir/output.log"; exit 1; }
  first_backup=$(find "$root/opt/audit-agent/frontend-backups" -mindepth 1 -maxdepth 1 -type d)
  cmp "$dist/index.html" "$package/variants/$variant/dist/index.html"
  run_update || { cat "$case_dir/output.log"; exit 1; }
  sha256sum -c "$case_dir/protected.sha256" >/dev/null
  bash "$package/rollback.sh" "$first_backup" > "$case_dir/rollback.log" 2>&1 || { cat "$case_dir/rollback.log"; exit 1; }
  grep -Fxq "OLD $variant ENTRY" "$dist/index.html"
  grep -Fxq 'NEW HEADER' "$dist/assets/new.js"
  sha256sum -c "$case_dir/protected.sha256" >/dev/null
done
make_case http_failure r2
export AUDIT_TEST_FAIL=assets
if run_update; then exit 1; fi
grep -Fxq 'OLD r2 ENTRY' "$dist/index.html"
sha256sum -c "$case_dir/protected.sha256" >/dev/null
make_case unknown_version r2
printf 'USER ENTRY EDIT\n' > "$dist/index.html"
if run_update; then exit 1; fi
grep -Fxq 'USER ENTRY EDIT' "$dist/index.html"
sha256sum -c "$case_dir/protected.sha256" >/dev/null
make_case edit_after_update r5
run_update || { cat "$case_dir/output.log"; exit 1; }
first_backup=$(find "$root/opt/audit-agent/frontend-backups" -mindepth 1 -maxdepth 1 -type d)
printf 'USER ENTRY EDIT\n' > "$dist/index.html"
if bash "$package/rollback.sh" "$first_backup" > "$case_dir/rollback.log" 2>&1; then exit 1; fi
grep -Fxq 'USER ENTRY EDIT' "$dist/index.html"
sha256sum -c "$case_dir/protected.sha256" >/dev/null
make_case manifest_tampering r2
printf 'TAMPERED FONT\n' > "$package/variants/r2/dist/assets/new.js"
if run_update; then exit 1; fi
grep -Fxq 'OLD r2 ENTRY' "$dist/index.html"
make_case missing_original_asset r2
rm "$dist/assets/old.js"
if run_update; then exit 1; fi
grep -Fxq 'OLD r2 ENTRY' "$dist/index.html"
make_case existing_asset_collision r2
printf 'USER ASSET\n' > "$dist/assets/new.js"
if run_update; then exit 1; fi
grep -Fxq 'USER ASSET' "$dist/assets/new.js"
grep -Fxq 'OLD r2 ENTRY' "$dist/index.html"
echo 'HEADER_UPDATE_R5_R2_REPEAT_HTTP_FAILURE_MANUAL_ROLLBACK_UNKNOWN_VERSION_NGINX_BACKEND_ENV_NO_SERVICE_MUTATION=PASS'
