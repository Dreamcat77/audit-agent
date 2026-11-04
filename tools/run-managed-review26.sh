#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077
root=$(pwd -P)
test -f .runtime/managed-review-20261020/candidate-classes.jar
set -a
. /home/admin/audit_ai_runtime/audit-ai.env
set +a
export PYTHONPATH="$root/services/audit-ai/src"
export AUDIT_MATERIAL_CHECKPOINT_ROOT="$root/.runtime/managed-review-20261020/checkpoints"
export AUDIT_OCR_CPU_THREADS=1 AUDIT_OCR_ENABLE_MKLDNN=false
output="$root/.runtime/managed-review-20261020"
fixtures=/home/admin/budget-extended-20261020.EicHyO/.runtime/budget-extended/fixtures
cp "$fixtures"/*.txt "$fixtures"/*.docx "$fixtures"/*.xlsx "$fixtures"/*.pdf "$output/"
mkdir -p "$AUDIT_MATERIAL_CHECKPOINT_ROOT"
python_bin=/opt/python/venvs/audit-ai/bin/python
"$python_bin" -m uvicorn audit_ai.main:app --host 127.0.0.1 --port 18095 --workers 1 >"$output/ai.log" 2>&1 &
ai_pid=$!
cleanup() { kill "$ai_pid" 2>/dev/null || true; wait "$ai_pid" 2>/dev/null || true; }
trap cleanup EXIT
for attempt in $(seq 1 30); do
  if curl --fail --silent http://127.0.0.1:18095/health >"$output/health.json"; then break; fi
  kill -0 "$ai_pid"
  sleep 1
done
curl --fail --silent http://127.0.0.1:18095/health
export PATH="/opt/java/current/bin:$PATH"
classpath="$output/candidate-classes.jar:/home/admin/reliability-v2-deploy26-20261019.bBEkIJ/runtime/BOOT-INF/lib/*"
javac -encoding UTF-8 --release 17 -cp "$classpath" -d "$output" tools/BudgetModelAcceptance.java
java -cp "$output:$classpath" BudgetModelAcceptance http://127.0.0.1:18095 http://127.0.0.1:8001 \
  "$output/suite.json" "$output/actual-results.json" >"$output/acceptance.log" 2>&1
curl --fail --silent http://127.0.0.1:8001/health
printf '\nMANAGED_ISOLATED_ACCEPTANCE_DONE businessWrites=0\n'
