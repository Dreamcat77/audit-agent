#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077
[[ $EUID == 0 ]] || { echo ROOT_REQUIRED; exit 1; }
package=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
base=/opt/audit-agent
[[ ${1:-} == '' || ${1:-} == --check ]] || exit 2
exec 9>/run/audit-agent-offline-install.lock
flock -n 9
die(){ echo "MATERIAL_TIMEOUT_DEPLOY_FAILED reasonCode=$1"; exit 1; }
(cd "$package" && sha256sum -c manifest.sha256 >/dev/null) || die PACKAGE_HASH
[[ -z $(find "$package/payload" -type l -print -quit) ]] || die PAYLOAD_SYMLINK
previous=$(readlink -f "$base/current")
read -r expected < "$package/previous-release.txt"
[[ $expected == /opt/audit-agent/releases/* && -L $base/current && $previous == "$expected" ]] || die RELEASE_CHANGED
(cd "$previous" && sha256sum -c "$package/previous-jar.sha256" >/dev/null) || die PREVIOUS_CODE_CHANGED
for unit in audit-backend audit-ai nginx; do systemctl is-active --quiet "$unit"; done
health(){ curl -fsS --max-time 5 http://127.0.0.1:8080/api/v1/health >/dev/null 2>&1 && curl -fsS --max-time 5 http://127.0.0.1:8001/health >/dev/null 2>&1; }
health || die PRIOR_HEALTH
mkdir -p "$package/runtime" "$package/classes"
(cd "$package/runtime" && /opt/java/current/bin/jar xf "$package/payload/backend/audit-backend-0.1.0-SNAPSHOT.jar" BOOT-INF/classes BOOT-INF/lib)
cp_path="$package/classes:$package/runtime/BOOT-INF/classes:$package/runtime/BOOT-INF/lib/*"
/opt/java/current/bin/javac --release 17 -encoding UTF-8 -cp "$cp_path" -d "$package/classes" "$package"/tools/*.java
set -a
source /etc/audit-agent/backend.env
set +a
guard(){ /opt/java/current/bin/java -cp "$cp_path" "$@"; }
guard MaterialDeploymentReadyCheck
guard BudgetUploadedBatchPlatformCheck check "$package/db/V052__budget_uploaded_material_batch.sql"
guard BudgetMaterialRiskPlatformCheck check "$package/db/V053__budget_material_risk_results.sql"
guard MaterialTransferPlatformCheck check "$package/db/V054__material_resumable_transfer.sql"
guard MaterialExtractionPlatformCheck check "$package/db/V055__material_extraction_ledger.sql"
guard ProcurementReportSnapshotPlatformCheck check "$package/db/V056__procurement_full_report_snapshot.sql"
guard MaterialDeadlineMigration check
(
 set -a
 source /home/admin/audit_ai_runtime/audit-ai.env
 set +a
 /opt/python/venvs/audit-ai/bin/python -I - "$package/payload/services/audit-ai/src" <<'PY'
import ast, importlib, pathlib, sys
assert sys.version_info[:2] == (3,11)
for name in ('fastapi','uvicorn','httpx','multipart'):importlib.import_module(name)
for p in pathlib.Path(sys.argv[1]).rglob('*.py'):ast.parse(p.read_text(encoding='utf-8'))
sys.path.insert(0,sys.argv[1])
from audit_ai.config import Settings
assert Settings.from_env().material_model_read_timeout_seconds==180
print('PYTHON_RUNTIME_SOURCE=PASS MATERIAL_MODEL_READ_TIMEOUT_SECONDS=180')
PY
)
if [[ ${1:-} == --check ]]; then echo APPLICATION_PREFLIGHT=PASS; exit 0; fi
backup=$(mktemp -d "$base/update-backups/material-timeout-20261019.XXXXXX")
mapfile -d '' configs < <(find /etc/audit-agent /etc/nginx /etc/systemd/system/audit-backend.service.d /etc/systemd/system/audit-ai.service.d -type f -print0 2>/dev/null | sort -z)
configs+=(/home/admin/audit_ai_runtime/audit-ai.env /etc/systemd/system/audit-backend.service /etc/systemd/system/audit-ai.service)
sha256sum "${configs[@]}" > "$backup/configs.sha256"
printf '%s\n' "$previous" > "$backup/previous.txt"
release=$(mktemp -d "$base/releases/material-timeout-20261019.XXXXXX")
chmod 0755 "$release"
cp -a "$previous/." "$release/"
cp "$package/payload/backend/audit-backend-0.1.0-SNAPSHOT.jar" "$release/backend/audit-backend-0.1.0-SNAPSHOT.jar"
[[ -d $release/services/audit-ai/src && ! -L $release/services/audit-ai/src ]] || die AI_LAYOUT
mv "$release/services/audit-ai/src" "$backup/staged-previous-ai-source"
cp -a "$package/payload/services/audit-ai/src" "$release/services/audit-ai/src"
[[ -d $release/frontend/dist && ! -L $release/frontend/dist ]] || die FRONTEND_LAYOUT
mv "$release/frontend/dist" "$backup/staged-previous-frontend"
cp -a "$package/payload/frontend/dist" "$release/frontend/dist"
cp "$package/VERSION.txt" "$release/VERSION.txt"
chown -R admin:admin "$release/backend" "$release/services/audit-ai/src" "$release/frontend/dist"
chmod 0644 "$release/backend/audit-backend-0.1.0-SNAPSHOT.jar"
find "$release/services/audit-ai/src" "$release/frontend/dist" -type d -exec chmod 0755 {} +
find "$release/services/audit-ai/src" "$release/frontend/dist" -type f -exec chmod 0644 {} +
while IFS= read -r -d '' file; do cmp -s "$file" "$release/${file#"$package/payload/"}" || die RELEASE_COPY_HASH; done < <(find "$package/payload" -type f -print0)
printf '%s\n' "$release" > "$backup/release.txt"
switched=no
stopped=no
wait_health(){ for n in {1..60}; do if health; then return 0; fi; sleep 2; done; return 1; }
restore(){
 set +e
 if [[ $switched == yes ]]; then ln -s "$previous" "$base/current.material-restore.$$"; mv -Tf "$base/current.material-restore.$$" "$base/current"; fi
 if [[ $stopped == yes ]]; then systemctl restart audit-ai audit-backend; if wait_health; then echo PREVIOUS_CODE_HEALTH_RESTORED; else echo RESTORE_HEALTH_FAILED; fi; fi
 echo "RECOVERY_RECORD=$backup V057_RETAINED=YES"
}
trap 'result=$?; if [[ $result != 0 ]]; then restore; fi' EXIT
trap 'exit 130' HUP INT TERM
guard MaterialDeploymentReadyCheck
stopped=yes
systemctl stop audit-backend audit-ai
guard MaterialDeadlineMigration apply
guard MaterialDeadlineMigration check
if command -v restorecon >/dev/null; then restorecon -RF "$release/backend" "$release/services/audit-ai/src" "$release/frontend/dist"; fi
ln -s "$release" "$base/current.material-next.$$"
mv -Tf "$base/current.material-next.$$" "$base/current"
switched=yes
systemctl start audit-ai audit-backend
wait_health || die NEW_HEALTH
sha256sum -c "$backup/configs.sha256" >/dev/null || die CONFIG_CHANGED
curl -fsS --max-time 10 http://127.0.0.1/ | cmp - "$release/frontend/dist/index.html"
mapfile -t assets < <(grep -oE '/assets/[a-zA-Z0-9_.-]+' "$release/frontend/dist/index.html" | sort -u)
[[ ${#assets[@]} -ge 2 ]] || die MISSING_ASSETS
for asset in "${assets[@]}"; do curl -fsS --max-time 20 "http://127.0.0.1$asset" | cmp - "$release/frontend/dist$asset"; done
for endpoint in procurement/projects budget/material-uploads/operations/00000000-0000-0000-0000-000000000000; do
 [[ $(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' "http://127.0.0.1/api/v1/$endpoint") == 401 ]] || die AUTH_BOUNDARY
done
curl -fsS --max-time 10 http://127.0.0.1/api/v1/health
curl -fsS --max-time 10 http://127.0.0.1:8001/health
printf '\nMATERIAL_TIMEOUT_DEPLOY26=PASS\nRELEASE=%s\nRECOVERY=%s\nCONFIG_HASHES=UNCHANGED\nHTTP_ASSETS=%s\n' "$release" "$backup" "${#assets[@]}"
trap - EXIT HUP INT TERM
