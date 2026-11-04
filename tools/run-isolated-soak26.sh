#!/usr/bin/env bash
# Candidate sources and generated fixtures only. Never changes the deployed release or business data.
set -Eeuo pipefail
set +x
umask 077
root=$(pwd -P)
export PATH="/opt/java/current/bin:/opt/maven/bin:$PATH"
if [[ "${1:-}" == --resume-java-verified ]]; then
    grep -q 'BUILD SUCCESS' backend-verify.log
    test -s backend/target/audit-backend-0.1.0-SNAPSHOT.jar
else
    printf 'SOAK_STAGE=JAVA17_VERIFY_START\n'
    java -version
    (cd backend && mvn -B clean verify > "$root/backend-verify.log" 2>&1)
    tail -n 16 backend-verify.log
fi
printf 'SOAK_STAGE=JAVA17_VERIFY_PASS\n'
python_bin=/opt/python/venvs/audit-ai/bin/python
(cd services/audit-ai && PYTHONPATH="$root/test-support:$PWD/src" "$python_bin" -m pytest -q --basetemp="$root/pytest-tmp" > "$root/python-verify.log" 2>&1)
tail -n 8 python-verify.log
printf 'SOAK_STAGE=PYTHON_VERIFY_PASS\n'
printf 'SOAK_STAGE=ISOLATED_RUNTIME_PREPARE\n'
mkdir -p runtime
(cd runtime && jar xf ../backend/target/audit-backend-0.1.0-SNAPSHOT.jar)
set -a
. /home/admin/audit_ai_runtime/audit-ai.env
set +a
export PYTHONPATH="$root/services/audit-ai/src"
export AUDIT_MATERIAL_CHECKPOINT_ROOT="$root/checkpoints"
export AUDIT_OCR_CPU_THREADS=1 AUDIT_OCR_ENABLE_MKLDNN=false
mkdir -p "$AUDIT_MATERIAL_CHECKPOINT_ROOT" runtime
if curl --silent --fail --max-time 2 http://127.0.0.1:18095/health >/dev/null; then
    printf 'SOAK_STOP=ISOLATED_PORT_ALREADY_IN_USE\n'; exit 2
fi
"$python_bin" -m uvicorn audit_ai.main:app --host 127.0.0.1 --port 18095 --workers 1 > ai.log 2>&1 &
ai_pid=$!
cleanup() { kill "$ai_pid" 2>/dev/null || true; wait "$ai_pid" 2>/dev/null || true; }
trap cleanup EXIT
for attempt in $(seq 1 30); do
    if curl --silent --fail --max-time 2 http://127.0.0.1:18095/health > health.json; then break; fi
    kill -0 "$ai_pid"; sleep 1
done
curl --silent --fail http://127.0.0.1:18095/health
classpath="$root/runtime/BOOT-INF/classes:$root/runtime/BOOT-INF/lib/*"
javac -encoding UTF-8 --release 17 -cp "$classpath" -d runtime tools/BudgetModelAcceptance.java
fixtures="$root/.runtime/budget-extended/fixtures"
for suite in suite formats; do
    printf '\nSOAK_STAGE=REAL_PROVIDER_%s_START\n' "$suite"
    java -cp "$root/runtime:$classpath" BudgetModelAcceptance http://127.0.0.1:18095 http://127.0.0.1:8001 \
        "$fixtures/$suite.json" "$root/$suite-results.json" > "$suite-acceptance.log" 2>&1
    tail -n 4 "$suite-acceptance.log"
done
printf 'SOAK_STAGE=REAL_PROVIDER_PROCUREMENT_BUDGET_MONEY_START\n'
"$python_bin" tools/test-commercial-material-money.py > commercial-money.log 2>&1
cat commercial-money.log
curl --silent --fail http://127.0.0.1/api/v1/health
printf '\nISOLATED_SOAK_DONE businessWrites=0 deployedServicesChanged=0\n'
