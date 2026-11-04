#!/usr/bin/env bash
set +x
install_root=/
package_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
base="${install_root%/}/opt/audit-agent"
etc="${install_root%/}/etc/audit-agent"
unit_root="${install_root%/}/etc/systemd/system"
java="${install_root%/}/opt/java/current/bin/java"
jar="${install_root%/}/opt/java/current/bin/jar"
python="${install_root%/}/opt/python/venvs/audit-ai/bin/python"
switch_current(){ local target=$1; local tmp="$base/current.v10-switch.$$"; ln -s "$target" "$tmp"; mv -Tf "$tmp" "$base/current"; }
restore_dropins(){
  # Validate both paths before changing either service's configuration.
  for svc in backend ai; do
    local path="$unit_root/audit-$svc.service.d/99-audit-v10.conf"
    [[ ! -L $path ]] || return 1
    if [[ -e $path ]]; then
      [[ -f $path && ! -L $path && -n ${record:-} && -f $record/$svc.conf ]] || return 1
      cmp -s "$path" "$record/$svc.conf" || return 1
    fi
  done
  for svc in backend ai; do
    local path="$unit_root/audit-$svc.service.d/99-audit-v10.conf"
    if [[ -e $path ]]; then
      if [[ -f $record/previous-$svc.conf ]]; then
        install -m 0644 "$record/previous-$svc.conf" "$path"
      else
        rm -f -- "$path"
      fi
    elif [[ -f ${record:-}/previous-$svc.conf ]]; then
      install -m 0644 "$record/previous-$svc.conf" "$path"
    fi
  done
}
check_managed_dropins(){
  local svc path expected actual
  for svc in backend ai; do
    path="$unit_root/audit-$svc.service.d/99-audit-v10.conf"
    [[ ! -L $path ]] || die OWN_DROPIN_NOT_REGULAR
    if [[ -e $path ]]; then
      [[ -f $path ]] || die OWN_DROPIN_NOT_REGULAR
      expected=$(printf '[Service]\nEnvironmentFile=%s/current/deploy/update-v10/%s.env\n' "$base" "$svc")
      actual=$(cat -- "$path")
      [[ "$actual" == "$expected" ]] || die OWN_DROPIN_CONTENT_MISMATCH
    fi
  done
}
die(){ echo "UPDATE_BLOCKED stage=${stage:-INPUT} reasonCode=$1"; exit 1; }
lock_update(){ exec 9>"${install_root%/}/run/audit-agent-offline-install.lock"; flock -n 9 || die UPDATE_LOCKED; }
verify_package(){
  [[ -z $(find "$package_dir" -type l -print -quit) ]] || die PACKAGE_SYMLINK;
  "$python" -I "$package_dir/package-check.py" "$package_dir" || die PACKAGE_CONTENTS;
}
loopback(){ [[ $1 =~ ^http://127\.0\.0\.1(:[0-9]{1,5})?/?$ ]]; }
load_record_http(){
  [[ -f $record/url.txt && ! -L $record/url.txt && -f $record/host.txt && ! -L $record/host.txt ]] || die ROLLBACK_HTTP_RECORD;
  http_base=$(cat -- "$record/url.txt"); host_value=$(cat -- "$record/host.txt"); host_header=()
  loopback "$http_base" || die LOOPBACK_URL_REQUIRED
  [[ -z $host_value || $host_value =~ ^[A-Za-z0-9_.:-]+$ ]] || die INVALID_HOST
  http_base=${http_base%/}
  [[ -z $host_value ]] || host_header=(-H "Host: $host_value")
}
curl_local(){ curl --noproxy '*' --proxy '' --silent --show-error --fail --max-time 15 --connect-timeout 5 -H 'Accept-Encoding: identity' -H 'Cache-Control: no-cache' "${host_header[@]}" "$@"; }
health(){ curl_local "$http_base/api/v1/health" >/dev/null 2>&1 && curl_local http://127.0.0.1:8001/health >/dev/null 2>&1; }
wait_health(){ for attempt in {1..90}; do if health; then return 0; fi; sleep 2; done; return 1; }
config_hashes(){
  { find "$etc" "${install_root%/}/etc/nginx" "$unit_root/audit-backend.service.d" "$unit_root/audit-ai.service.d" -type f ! -name '99-audit-v10.conf' -print0 2>/dev/null || true; } | sort -z | xargs -0 -r sha256sum
  for file in "$unit_root/audit-backend.service" "$unit_root/audit-ai.service"; do [[ ! -f $file ]] || sha256sum "$file"; done
}
load_db(){
  [[ -f $etc/backend.env && ! -L $etc/backend.env ]] || die BACKEND_ENV_MISSING;
  set -a; source "$etc/backend.env"; set +a; set +x
  [[ ${AUDIT_DB_URL:-} == jdbc:dm://* && -n ${AUDIT_DB_USERNAME:-} && -n ${AUDIT_DB_PASSWORD:-} ]] || die DB_ENV_INCOMPLETE;
  [[ ${AUDIT_DB_USERNAME^^} != SYSDBA && ${AUDIT_DB_USERNAME^^} != SYSAUDITOR ]] || die DB_ADMIN_ACCOUNT_FORBIDDEN;
}
prepare_runtime(){
  [[ -x $java && -x $jar && -x $python ]] || die EXISTING_RUNTIME_NOT_EXECUTABLE;
  runtime=$(mktemp -d "$package_dir/.check-runtime.XXXXXXXX"); chmod 0700 "$runtime";
  (cd "$runtime" && "$jar" xf "$package_dir/payload/backend/audit-backend-0.1.0-SNAPSHOT.jar" BOOT-INF/classes BOOT-INF/lib);
  classpath="$package_dir/tools:$runtime/BOOT-INF/classes:$runtime/BOOT-INF/lib/*"
}
guard(){ "$java" -cp "$classpath" "$@"; }
schema(){
  local mode=$1; stage=DATABASE_PREFLIGHT
  guard BudgetApprovalMigration "$mode" "$package_dir/db/V016__f04_budget_approval_program.sql"
  guard MaterialOrderMigration "$mode" "$record/order.txt"
  guard MaterialAsyncMigration "$mode" "$record/async.txt"
  guard BudgetSubjectWidthMigration "$mode"
  guard BudgetMaterialMigration "$mode" "$package_dir/db/V051__budget_material_background.sql"
  for row in 'BudgetUploadedBatchPlatformCheck V052__budget_uploaded_material_batch.sql' 'BudgetMaterialRiskPlatformCheck V053__budget_material_risk_results.sql' 'MaterialTransferPlatformCheck V054__material_resumable_transfer.sql' 'MaterialExtractionPlatformCheck V055__material_extraction_ledger.sql' 'ProcurementReportSnapshotPlatformCheck V056__procurement_full_report_snapshot.sql'; do
    read -r helper sql <<< "$row"
    local output
    output=$(guard "$helper" "$([[ $mode == verify ]] && echo check || echo "$mode")" "$package_dir/db/$sql") || return 1
    echo "$output"
    if [[ $mode == verify && $output == *NOT_APPLIED* ]]; then die ADDITIVE_SCHEMA_NOT_APPLIED; fi
  done
  # V057 depends on V055; an absent V055 is an approved planned prerequisite.
  guard CustomerReadiness deadline "$mode"
  guard CustomerContractSchema "$mode" "$package_dir/schema.json" "$record/contract-ddl"
}
readiness(){ guard CustomerReadiness ready; }
check_http(){
  local release=$1 mode=${2:-verify} file relative; stage=HTTP_RESOURCES
  if [[ $mode == existing ]]; then
    # Old UI patches may leave inaccessible, unused files. They are replaced by
    # this release and must not prevent a repair. Verify the new release strictly.
    if curl_local "$http_base/" | cmp - "$release/frontend/dist/index.html" >/dev/null 2>&1; then
      echo EXISTING_HTTP_ENTRY=PASS
    else
      echo 'EXISTING_HTTP_ENTRY=WARN; new release will be verified after switching'
    fi
  elif [[ $mode == verify ]]; then
    curl_local "$http_base/" | cmp - "$release/frontend/dist/index.html" >/dev/null 2>&1 || die HTTP_INDEX_ROOT_MISMATCH
    while IFS= read -r -d '' file; do
      relative=${file#"$release/frontend/dist/"}; [[ $relative != index.html ]] || continue
      if ! curl_local "$http_base/$relative" | cmp - "$file" >/dev/null 2>&1; then
        printf 'HTTP_RESOURCE_FAILED asset=%q\n' "$relative"
        die HTTP_ASSET_MISMATCH
      fi
    done < <(find "$release/frontend/dist" -type f -print0)
  else
    die HTTP_CHECK_MODE_INVALID
  fi
  for endpoint in projects procurement/projects budget/material-uploads/operations/00000000-0000-0000-0000-000000000000; do
    code=$(curl --noproxy '*' --proxy '' -sS --max-time 10 "${host_header[@]}" -o /dev/null -w '%{http_code}' "$http_base/api/v1/$endpoint")
    [[ $code == 401 ]] || die AUTH_BOUNDARY;
  done
  if [[ $mode == existing ]]; then echo HTTP_AUTH=PASS; else echo HTTP_INDEX_ASSETS_AUTH=PASS; fi
}
