#!/usr/bin/env bash
# Read-only table inventory against the locally checked-out DM8 migration baseline.
set -euo pipefail
set +x
umask 077
[[ $# == 0 ]] || { echo 'Usage: sudo bash audit-db-schema-compare.sh'; exit 2; }
[[ ${EUID:-$(id -u)} == 0 ]] || { echo 'Run as root.'; exit 2; }
[[ -r /etc/audit-agent/backend.env ]] || { echo 'Backend configuration unavailable.'; exit 2; }
java_bin=/opt/java/current/bin/java
jar_bin=/opt/java/current/bin/jar
backend_jar=/opt/audit-agent/current/backend/audit-backend-0.1.0-SNAPSHOT.jar
[[ -r $backend_jar ]] || backend_jar=/opt/audit-agent/current/release/backend/audit-backend-0.1.0-SNAPSHOT.jar
[[ -x $java_bin && -x $jar_bin && -r $backend_jar ]] || { echo 'Installed JDK/backend JAR unavailable.'; exit 2; }

work=$(mktemp -d /tmp/audit-schema-check.XXXXXXXX)
trap 'rm -rf -- "$work"' EXIT
report=$(mktemp "./audit-db-schema-$(date +%Y%m%d-%H%M%S).XXXXXXXX.txt")
printf 'SCHEMA_BASELINE=LOCAL_GIT_0b64c63\nSCHEMA_SCOPE=85_TABLES_FROM_DB_DM8_MIGRATIONS\n' > "$report"
set -a
# shellcheck disable=SC1091
source /etc/audit-agent/backend.env
set +a
set +x
if [[ ${AUDIT_DB_USERNAME:-} != AUDIT_APP || ${AUDIT_DB_URL:-} != jdbc:dm://* || -z ${AUDIT_DB_PASSWORD:-} ]]; then
  printf 'SCHEMA_CONFIG=UNKNOWN_OR_UNSUPPORTED\n' >> "$report"
  echo "Report: $report"
  exit 1
fi
driver=$("$jar_bin" tf "$backend_jar" 2>/dev/null | sed -n '/^BOOT-INF\/lib\/DmJdbcDriver[^/]*\.jar$/p' | head -n 1)
if [[ -z $driver ]] || ! (cd "$work" && "$jar_bin" xf "$backend_jar" "$driver") >/dev/null 2>&1; then
  printf 'SCHEMA_DRIVER=UNAVAILABLE\n' >> "$report"
  echo "Report: $report"
  exit 1
fi

cat > "$work/AuditSchemaCompare.java" <<'JAVA'
import java.sql.*;
import java.util.*;

class AuditSchemaCompare {
    // Unique CREATE TABLE names from db/dm8/Vxxx__*, CONTRACT__*, PROCUREMENT__*
    // in local commit 0b64c63. Migration application is not inferred from file presence.
    static final String[] TABLES = (
        "AUDIT_CHECK_RESULT AUDIT_OPERATION_LOG AUDIT_TASK AUTH_LOGIN_AUDIT " +
        "BLACKLIST_ALERT BLACKLIST_RULE BLACKLIST_SUPPLIER BLACKLIST_SUPPLIER_OPERATION_LOG " +
        "BUDGET_APPROVAL_CHECK BUDGET_APPROVAL_RULE_CONFIG BUDGET_APPROVAL_VALIDATION " +
        "BUDGET_CATEGORY_RULES BUDGET_PROJECT_CONFIG BUDGET_REVIEW_ITEM BUDGET_REVIEW_TASK " +
        "BUDGET_SPLIT_RISK_ASSESSMENT BUDGET_SPLIT_RISK_RULE BUDGET_SUBJECT_MAPPING " +
        "BUDGET_VALIDATION_CONFIRM_LOG BUDGET_YEAR_VALIDITY_CONFIG BUSINESS_MATERIAL_INTAKE " +
        "CONTRACT CONTRACT_AWARD_NOTICE CONTRACT_CANDIDATE CONTRACT_CANDIDATE_CONFIRMATION " +
        "CONTRACT_CANDIDATE_LINE_ITEM CONTRACT_CANDIDATE_SUPPORT_AUTHORITY CONTRACT_LINE_ITEM " +
        "CONTRACT_MODEL_CALL_AUDIT CONTRACT_REVIEW_CLAUSE_COMPARISON CONTRACT_REVIEW_CLAUSE_VALUE " +
        "CONTRACT_REVIEW_CLAUSE_VALUE_EVIDENCE CONTRACT_REVIEW_DOCUMENT CONTRACT_REVIEW_EVIDENCE " +
        "CONTRACT_REVIEW_FINDING CONTRACT_REVIEW_FINDING_ACKNOWLEDGEMENT " +
        "CONTRACT_REVIEW_MATERIAL_BINDING CONTRACT_REVIEW_REPORT CONTRACT_REVIEW_RUN " +
        "CONTRACT_REVIEW_RUN_MATERIAL CONTRACT_REVIEW_SESSION CONTRACT_REVIEW_STEP " +
        "CONTRACT_REVIEW_STEP_ATTEMPT CONTRACT_RULE_CONFIG IDEMPOTENCY_RECORD " +
        "PROCUREMENT_AGENT_REVIEW_SNAPSHOT PROCUREMENT_APPROVAL_INSTANCE PROCUREMENT_APPROVAL_NODE " +
        "PROCUREMENT_APPROVAL_OPERATION_LOG PROCUREMENT_COLLUSION_ASSESSMENT " +
        "PROCUREMENT_COLLUSION_DOCUMENT PROCUREMENT_DEMAND PROCUREMENT_DEMAND_MATERIAL " +
        "PROCUREMENT_MATERIAL_LINK PROCUREMENT_NOTIFICATION PROCUREMENT_QUOTE_CHECK " +
        "PROCUREMENT_QUOTE_CHECK_ITEM PROCUREMENT_QUOTE_COMPARISON PROCUREMENT_REVIEW_REPORT " +
        "PROCUREMENT_REVIEW_STEP PROCUREMENT_REVIEW_WORKFLOW PROCUREMENT_SPLIT_PURCHASE_ASSESSMENT " +
        "PROCUREMENT_TENDER_DOCUMENT PROCUREMENT_TENDER_FINDING PROCUREMENT_TENDER_RULE " +
        "PROJECT PROJECT_CONTRACT PROJECT_EXTRACTION_RESULT PROJECT_FACT_EXTRACTION_ATTEMPT " +
        "PROJECT_FACT_EXTRACTION_CANDIDATE PROJECT_FACT_EXTRACTION_REJECTION PROJECT_FILE " +
        "PROJECT_FILE_IDENTITY_ASSESSMENT PROJECT_FILE_IDENTITY_DECISION " +
        "PROJECT_FILE_IDENTITY_EVIDENCE PROJECT_FILE_PARSE_RESULT PROJECT_FILE_ROLE_ASSESSMENT " +
        "PROJECT_FILE_ROLE_DECISION PROJECT_FILE_ROLE_EVIDENCE REVIEW_FINDING_ACKNOWLEDGEMENT " +
        "REVIEW_SESSION_PROJECT_FILE_BINDING SANITIZED_DEBUG_BUNDLE SYS_ROLE SYS_USER SYS_USER_ROLE"
    ).split(" ");
    static void emit(String key, String value) { System.out.println("SCHEMA_" + key + "=" + value); }
    static String key(String owner, String table) { return owner + "." + table; }
    static void check(Connection c) throws SQLException {
        Set<String> physical = new HashSet<>(), synonyms = new HashSet<>();
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery(
                "SELECT OWNER,TABLE_NAME FROM ALL_TABLES WHERE OWNER IN ('AUDIT_APP','SYSDBA')")) {
            while (r.next()) physical.add(key(r.getString(1), r.getString(2)));
        }
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery(
                "SELECT OWNER,SYNONYM_NAME FROM ALL_SYNONYMS WHERE OWNER IN ('AUDIT_APP','PUBLIC')")) {
            while (r.next()) synonyms.add(key(r.getString(1), r.getString(2)));
        }
        int present = 0, viaSynonym = 0, missing = 0, unknown = 0;
        for (String table : TABLES) {
            String owner = "PROCUREMENT_DEMAND".equals(table) ? "SYSDBA" : "AUDIT_APP";
            String status;
            if (physical.contains(key(owner, table))) {
                status = "PRESENT"; present++;
            } else if (synonyms.contains(key("AUDIT_APP", table)) ||
                       synonyms.contains(key("PUBLIC", table))) {
                try (Statement s = c.createStatement();
                     ResultSet r = s.executeQuery("SELECT 1 FROM AUDIT_APP." + table + " WHERE 1=0")) {
                    status = "VIA_SYNONYM"; viaSynonym++;
                } catch (SQLException error) {
                    status = "UNKNOWN"; unknown++;
                }
            } else {
                status = "MISSING"; missing++;
            }
            emit("TABLE_" + owner + "_" + table, status);
        }
        emit("SUMMARY_EXPECTED", Integer.toString(TABLES.length));
        emit("SUMMARY_PRESENT", Integer.toString(present));
        emit("SUMMARY_VIA_SYNONYM", Integer.toString(viaSynonym));
        emit("SUMMARY_MISSING", Integer.toString(missing));
        emit("SUMMARY_UNKNOWN", Integer.toString(unknown));
    }
    public static void main(String[] args) {
        try {
            DriverManager.setLoginTimeout(8);
            Class.forName("dm.jdbc.driver.DmDriver");
            try (Connection c = DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),
                    System.getenv("AUDIT_DB_USERNAME"), System.getenv("AUDIT_DB_PASSWORD"))) {
                try { c.setReadOnly(true); } catch (SQLException ignored) { /* SELECT-only code. */ }
                check(c);
            }
        } catch (Throwable ignored) {
            // Driver/SQL errors can contain private connection details.
            emit("QUERY", "UNKNOWN");
        }
    }
}
JAVA

# The JVM's raw output and errors stay in a private temporary directory.
if timeout 90s "$java_bin" -cp "$work/$driver" "$work/AuditSchemaCompare.java" \
    > "$work/jvm.out" 2> "$work/jvm.err"; then
  grep -E '^SCHEMA_(TABLE_(AUDIT_APP|SYSDBA)_[A-Z0-9_]+|SUMMARY_(EXPECTED|PRESENT|VIA_SYNONYM|MISSING|UNKNOWN)|QUERY)=(PRESENT|VIA_SYNONYM|MISSING|UNKNOWN|[0-9]+)$' \
    "$work/jvm.out" >> "$report" || true
else
  printf 'SCHEMA_QUERY=UNKNOWN\n' >> "$report"
fi
if ! grep -qx 'SCHEMA_SUMMARY_EXPECTED=85' "$report"; then
  printf 'RESULT=INCONCLUSIVE\n' >> "$report"
else
  printf 'RESULT=TABLE_INVENTORY_COMPLETE\n' >> "$report"
fi
echo "Report: $report"
echo 'Send only this report; do not send backend.env or raw database logs.'
