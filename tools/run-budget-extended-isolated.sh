#!/usr/bin/env bash
set -euo pipefail
umask 077
root="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
cd "$root"
test -f .runtime/budget-extended/candidate-classes.jar
test -f .runtime/budget-extended/fixtures/suite.json
test -f /home/admin/audit_ai_runtime/audit-ai.env
set -a
. /home/admin/audit_ai_runtime/audit-ai.env
set +a
export PYTHONPATH="$root/services/audit-ai/src"
export AUDIT_MATERIAL_CHECKPOINT_ROOT="$root/.runtime/budget-extended/checkpoints"
export AUDIT_OCR_CPU_THREADS=1
export AUDIT_OCR_ENABLE_MKLDNN=false
python_bin=/opt/python/venvs/audit-ai/bin/python
mkdir -p "$AUDIT_MATERIAL_CHECKPOINT_ROOT"
"$python_bin" -m uvicorn audit_ai.main:app --host 127.0.0.1 --port 18095 --workers 1 \
  >.runtime/budget-extended/ai.log 2>&1 &
ai_pid=$!
cleanup() { kill "$ai_pid" 2>/dev/null || true; wait "$ai_pid" 2>/dev/null || true; }
trap cleanup EXIT
for attempt in $(seq 1 30); do
  if curl --fail --silent http://127.0.0.1:18095/health >.runtime/budget-extended/health.json; then break; fi
  kill -0 "$ai_pid"
  sleep 1
done
curl --fail --silent http://127.0.0.1:18095/health
export PATH="/opt/java/current/bin:$PATH"
jar_file=$(readlink -f /opt/audit-agent/current/backend/audit-backend-0.1.0-SNAPSHOT.jar)
if test ! -f "$jar_file"; then
  printf '\nISOLATED_JAR_PATH_MISSING\n'
  exit 2
fi
mkdir -p .runtime/budget-extended/lib
cd .runtime/budget-extended/lib
jar xf "$jar_file" BOOT-INF/lib
cd "$root"
classpath="$root/.runtime/budget-extended/candidate-classes.jar:$root/.runtime/budget-extended/lib/BOOT-INF/lib/*"
javac -encoding UTF-8 --release 17 -cp "$classpath" -d .runtime/budget-extended tools/BudgetModelAcceptance.java
java -cp "$root/.runtime/budget-extended:$classpath" BudgetModelAcceptance \
  http://127.0.0.1:18095 http://127.0.0.1:8001 \
  .runtime/budget-extended/fixtures/suite.json .runtime/budget-extended/actual-results.json \
  >.runtime/budget-extended/acceptance.log 2>&1
java -cp "$root/.runtime/budget-extended:$classpath" BudgetModelAcceptance \
  http://127.0.0.1:18095 http://127.0.0.1:8001 \
  .runtime/budget-extended/fixtures/formats.json .runtime/budget-extended/format-results.json \
  >.runtime/budget-extended/formats.log 2>&1
curl --fail --silent http://127.0.0.1:8001/health
printf '\nISOLATED_BUDGET_EXTENDED_DONE businessWrites=0\n'
