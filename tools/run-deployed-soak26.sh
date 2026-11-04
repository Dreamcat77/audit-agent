#!/usr/bin/env bash
# End-to-end checks create only generated synthetic records and disable their actor on exit.
set -Eeuo pipefail
set +x
umask 077
cd -- "$(dirname -- "$0")"
export PATH="/opt/java/current/bin:$PATH"
classpath="$PWD/runtime/BOOT-INF/classes:$PWD/runtime/BOOT-INF/lib/*"
javac -encoding UTF-8 --release 17 -cp "$classpath" -d runtime tools/BudgetBackgroundRunner.java
set -a
. /etc/audit-agent/backend.env
set +a
for mode in budget procurement; do
    printf 'DEPLOYED_SYNTHETIC_STAGE=%s_START\n' "$mode"
    java -cp "$PWD/runtime:$classpath" BudgetBackgroundRunner "$PWD/review_probe.py" "$mode" > "$mode-http.log" 2>&1
    tail -n 5 "$mode-http.log"
done
printf 'DEPLOYED_SYNTHETIC_STAGE=OFFICE_FORMATS_START\n'
java -cp "$PWD/runtime:$classpath" BudgetBackgroundRunner "$PWD/test-office-formats26.py" all > office-http.log 2>&1
tail -n 5 office-http.log
printf 'DEPLOYED_SYNTHETIC_HTTP_DONE originalBusinessRecordsChanged=0\n'
