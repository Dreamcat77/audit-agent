#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
package_version=$(sed -n 's/^PACKAGE_VERSION=//p' "$package_dir/VERSION.txt")
[[ $package_version =~ ^v[0-9]+[a-z]?$ ]] || die VERSION_INVALID
check_only=no; http_base=http://127.0.0.1; host_header=(); host_value=''
while (( $# )); do
  case "$1" in
    --check) check_only=yes; shift ;;
    --url) http_base=${2:?}; shift 2 ;;
    --host) host_value=${2:?}; shift 2 ;;
    *) die ARGUMENTS ;;
  esac
done
loopback "$http_base" || die LOOPBACK_URL_REQUIRED
[[ -z $host_value || $host_value =~ ^[A-Za-z0-9_.:-]+$ ]] || die INVALID_HOST
[[ -z $host_value ]] || host_header=(-H "Host: $host_value")
http_base=${http_base%/}
[[ ${EUID:-$(id -u)} == 0 ]] || die ROOT_REQUIRED
stage=PRECHECK; lock_update; verify_package
cat "$package_dir/VERSION.txt"
arch=$(uname -m)
target=$(sed -n 's/^TARGET=//p' "$package_dir/VERSION.txt")
case "$arch:$target" in
  aarch64:*_AARCH64_EXISTING_INSTALL|x86_64:*_X86_64_EXISTING_INSTALL) ;;
  x86_64:*_AARCH64_EXISTING_INSTALL|aarch64:*_X86_64_EXISTING_INSTALL) die TARGET_ARCH_MISMATCH ;;
  *) die TARGET_ARCH_UNKNOWN ;;
esac
previous=$(readlink -f "$base/current")
case "$previous" in "$base/releases/"*) ;; *) die CURRENT_RELEASE_LAYOUT ;; esac
[[ -L $base/current && -d $previous/backend && -d $previous/frontend/dist && -d $previous/services/audit-ai/src/audit_ai ]] || die CURRENT_RELEASE_LAYOUT
[[ -z $(find "$previous/backend" "$previous/frontend/dist" "$previous/services/audit-ai/src" -type l -print -quit) ]] || die SOURCE_SYMLINK
check_managed_dropins
if [[ -e $previous/deploy/update-v10 || -L $previous/deploy/update-v10 ]]; then
  [[ -d $previous/deploy/update-v10 && ! -L $previous/deploy/update-v10 ]] || die OWN_RELEASE_MARKER_NOT_REGULAR
  [[ -z $(find "$previous/deploy/update-v10" -type l -print -quit) ]] || die SOURCE_SYMLINK
fi
systemctl is-active --quiet audit-backend && systemctl is-active --quiet audit-ai && systemctl is-active --quiet nginx || die EXISTING_SERVICE_INACTIVE
nginx -t || die NGINX_SYNTAX
health || die EXISTING_HEALTH
check_http "$previous" existing
prepare_runtime
cleanup_preflight(){
  if [[ -n ${probe:-} && $probe == "${install_root%/}/tmp/audit-v10-probe."* && -d $probe ]]; then rm -r -- "$probe"; fi
  if [[ -n ${runtime:-} && $runtime == "$package_dir"/.check-runtime.* && -d $runtime ]]; then rm -r -- "$runtime"; fi
}
trap cleanup_preflight EXIT
stage=PYTHON_RUNTIME
service_user=$(systemctl show audit-ai.service -p User --value)
[[ $service_user =~ ^[a-zA-Z_][a-zA-Z0-9_-]*$ && $service_user != root ]] || die SERVICE_USER_REQUIRED
# Copy only the public probe and source to a traversable temporary directory.
probe=$(mktemp -d "${install_root%/}/tmp/audit-v10-probe.XXXXXXXX")
chmod 0755 "$probe"
cp "$package_dir/runtime-probe.py" "$probe/probe.py"; chmod 0644 "$probe/probe.py"
cp -a "$package_dir/payload/services/audit-ai/src" "$probe/src"
find "$probe/src" -type d -exec chmod 0755 {} +
find "$probe/src" -type f -exec chmod 0644 {} +
probe_result=0
runuser -u "$service_user" -- env OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True "$python" -I "$probe/probe.py" "$probe/src" > "$probe/result.txt" 2>&1 || probe_result=$?
# Only safe fixed probe status lines leave the private dependency log.
grep -E '^(PYTHON311_PADDLEOCR37_SOURCE_IMPORTS=PASS|RUNTIME_PROBE=FAIL reasonCode=[A-Z_]+ type=[A-Za-z]+)$' "$probe/result.txt" || true
[[ $probe == "${install_root%/}/tmp/audit-v10-probe."* ]] && rm -r -- "$probe"; probe=''
[[ $probe_result == 0 ]] || die RUNTIME_PROBE_FAILED
load_db
record="$runtime/records"
install -d -m 0700 "$record"
schema check; readiness
if [[ $check_only == yes ]]; then
  unset AUDIT_DB_PASSWORD
  echo 'CHECK=PASS；未迁移数据库、切换版本或重启服务。甲方模型尚需现场验证。'
  exit 0
fi
# This package intentionally has no operator-created recovery-record gate.
# Required schema and quiescence checks above remain fail-closed.
stage=STAGE_RELEASE
install -d -m 0700 "$base/update-backups"
record=$(mktemp -d "$base/update-backups/${package_version}.XXXXXXXX")
release=$(mktemp -d "$base/releases/${package_version}.XXXXXXXX")
chmod 0755 "$release"
printf '%s\n' "$previous" > "$record/previous.txt"
printf '%s\n' "$release" > "$record/release.txt"
printf '%s\n' "$http_base" > "$record/url.txt"
printf '%s\n' "$host_value" > "$record/host.txt"
for svc in backend ai; do
  old_dropin="$unit_root/audit-$svc.service.d/99-audit-v10.conf"
  if [[ -e $old_dropin ]]; then
    [[ -f $old_dropin && ! -L $old_dropin ]] || die OWN_DROPIN_NOT_REGULAR
    expected=$(printf '[Service]\nEnvironmentFile=%s/current/deploy/update-v10/%s.env\n' "$base" "$svc")
    actual=$(cat -- "$old_dropin")
    [[ "$actual" == "$expected" ]] || die OWN_DROPIN_CONTENT_MISMATCH
    cp -p -- "$old_dropin" "$record/previous-$svc.conf"
  fi
done
config_hashes > "$record/configs.sha256"
cp -a "$previous/." "$release/"
# Replace source trees instead of mixing old modules or JS with new ones.
mv "$release/frontend/dist" "$record/staged-frontend-before"
mv "$release/services/audit-ai/src" "$record/staged-ai-before"
cp -a "$package_dir/payload/." "$release/"
find "$release/backend" "$release/frontend/dist" "$release/services/audit-ai/src" "$release/services/audit-ai/models/PP-LCNet_x1_0_doc_ori" -type d -exec chmod 0755 {} +
find "$release/backend" "$release/frontend/dist" "$release/services/audit-ai/src" "$release/services/audit-ai/models/PP-LCNet_x1_0_doc_ori" -type f -exec chmod 0644 {} +
if [[ -e $release/deploy/update-v10 ]]; then
  [[ -d $release/deploy/update-v10 && ! -L $release/deploy/update-v10 ]] || die OWN_RELEASE_MARKER_NOT_REGULAR
  mv -- "$release/deploy/update-v10" "$record/inherited-update-v10"
fi
install -d -m 0755 "$release/deploy/update-v10"
printf '%s\n' 'AUDIT_BUDGET_MATERIAL_WORKER_ENABLED=true' 'AUDIT_PROCUREMENT_MATERIAL_WORKER_ENABLED=true' 'AUDIT_CONTRACT_FILE_PROCESSING_WORKER_ENABLED=true' 'AUDIT_PROCUREMENT_AUTO_CASE_ENABLED=true' 'AUDIT_CONTRACT_ANALYSIS_ENABLED=true' > "$release/deploy/update-v10/backend.env"
printf '%s\n' 'AUDIT_OCR_CPU_THREADS=1' 'AUDIT_OCR_ENABLE_MKLDNN=false' 'AUDIT_OCR_ORIENTATION_ENABLED=true' 'AUDIT_OCR_PROCESS_ISOLATION=true' 'AUDIT_MATERIAL_MODEL_READ_TIMEOUT_SECONDS=180' "AUDIT_OCR_ORIENTATION_MODEL_DIR=$base/current/services/audit-ai/models/PP-LCNet_x1_0_doc_ori" > "$release/deploy/update-v10/ai.env"
chmod 0644 "$release/deploy/update-v10/"*.env
for svc in backend ai; do
  dropin="$unit_root/audit-$svc.service.d/99-audit-v10.conf"
  if [[ -e $dropin ]]; then
    [[ -f $dropin && ! -L $dropin && -f $record/previous-$svc.conf ]] || die OWN_DROPIN_ALREADY_EXISTS
  fi
  printf '[Service]\nEnvironmentFile=%s/current/deploy/update-v10/%s.env\n' "$base" "$svc" > "$record/$svc.conf"
done
(cd "$record" && sha256sum previous.txt release.txt url.txt host.txt configs.sha256 backend.conf ai.conf && for prior in previous-backend.conf previous-ai.conf; do [[ ! -f $prior ]] || sha256sum "$prior"; done) > "$record/record.sha256"
stopped=no; switched=no; dropins=no
restore(){
  set +e
  if [[ $stopped == yes ]]; then
    systemctl stop audit-backend audit-ai
    if [[ $switched == yes ]]; then switch_current "$previous"; fi
    if [[ $dropins == yes ]]; then restore_dropins; fi
    systemctl daemon-reload
    systemctl start audit-ai audit-backend
    if wait_health; then echo 'ROLLBACK_HEALTH=PASS 原代码已恢复；已执行的增量DDL保留。'; else echo 'ROLLBACK_HEALTH=FAIL 请保留现场及日志人工排查。'; fi
  fi
  echo "应用恢复目录：$record"
}
finish(){ result=$?; trap - EXIT; if [[ $result != 0 ]]; then restore; fi; cleanup_preflight; exit "$result"; }
trap finish EXIT
trap 'exit 130' INT HUP TERM
stage=STOP_SERVICES
stopped=yes
systemctl stop audit-backend audit-ai
systemctl is-active --quiet audit-backend && die BACKEND_NOT_STOPPED
systemctl is-active --quiet audit-ai && die AI_NOT_STOPPED
# Re-check after the writers have stopped. No automatic retry of unknown model work.
schema check; readiness
stage=DATABASE_APPLY
schema apply
schema verify
unset AUDIT_DB_PASSWORD
sha256sum -c "$record/configs.sha256" >/dev/null || die CONFIG_CHANGED_BEFORE_SWITCH
stage=SWITCH_RELEASE
dropins=yes
for svc in backend ai; do
  install -d -m 0755 "$unit_root/audit-$svc.service.d"
  install -m 0644 "$record/$svc.conf" "$unit_root/audit-$svc.service.d/99-audit-v10.conf"
done
if command -v restorecon >/dev/null; then restorecon -RF "$release" "$unit_root/audit-backend.service.d" "$unit_root/audit-ai.service.d"; fi
switch_current "$release"; switched=yes
systemctl daemon-reload
systemctl start audit-ai audit-backend
stage=START_SERVICES
wait_health || die NEW_RELEASE_HEALTH
sha256sum -c "$record/configs.sha256" >/dev/null || die ORIGINAL_CONFIG_CHANGED
check_http "$release"
stopped=no; switched=no; dropins=no
echo SUCCESS > "$record/outcome.txt"
echo 'UPDATE=PASS。预算、采购、合同及公共AI/OCR代码已切换。请Ctrl+F5，并现场测试三类材料。'
echo '模型网关白名单及本地模型协议、ARM实际OCR与合同正式五步审查仍需现场验收。'
echo "应用恢复目录：$record"
