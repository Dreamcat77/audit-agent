#!/usr/bin/env bash
# Read-only procurement DM8 check. The report contains allowlisted codes only.
set -euo pipefail
set +x
umask 077
[[ $# == 0 ]] || { echo 'Usage: sudo bash audit-db-diagnose.sh'; exit 2; }
[[ ${EUID:-$(id -u)} == 0 ]] || { echo 'Run as root.'; exit 2; }
[[ -r /etc/audit-agent/backend.env ]] || { echo 'Backend configuration unavailable.'; exit 2; }

java_bin=/opt/java/current/bin/java
jar_bin=/opt/java/current/bin/jar
backend_jar=/opt/audit-agent/current/backend/audit-backend-0.1.0-SNAPSHOT.jar
[[ -r $backend_jar ]] || backend_jar=/opt/audit-agent/current/release/backend/audit-backend-0.1.0-SNAPSHOT.jar
[[ -x $java_bin && -x $jar_bin && -r $backend_jar ]] || { echo 'Installed JDK/backend JAR unavailable.'; exit 2; }

work=$(mktemp -d /tmp/audit-db-check.XXXXXXXX)
trap 'rm -rf -- "$work"' EXIT
report=$(mktemp "./audit-db-diagnostic-$(date +%Y%m%d-%H%M%S).XXXXXXXX.txt")
printf 'AUDIT_DB_DIAGNOSTIC=20261009.1\n' > "$report"
set -a
# shellcheck disable=SC1091
source /etc/audit-agent/backend.env
set +a
set +x
if [[ ${AUDIT_DB_USERNAME:-} != AUDIT_APP || ${AUDIT_DB_URL:-} != jdbc:dm://* || -z ${AUDIT_DB_PASSWORD:-} ]]; then
  printf 'CONFIG=UNKNOWN_OR_UNSUPPORTED\n' >> "$report"
  echo "Report: $report"
  exit 1
fi
driver=$("$jar_bin" tf "$backend_jar" 2>/dev/null | sed -n '/^BOOT-INF\/lib\/DmJdbcDriver[^/]*\.jar$/p' | head -n 1)
if [[ -z $driver ]] || ! (cd "$work" && "$jar_bin" xf "$backend_jar" "$driver") >/dev/null 2>&1; then
  printf 'JDBC_DRIVER=UNAVAILABLE\n' >> "$report"
  echo "Report: $report"
  exit 1
fi

cat > "$work/AuditDbDiagnose.java" <<'JAVA'
import java.sql.*;
import java.util.*;

class AuditDbDiagnose {
    static final String APP = "AUDIT_APP";
    static final Map<String, String[]> COLUMNS = new LinkedHashMap<>();
    static {
        COLUMNS.put("BUSINESS_MATERIAL_INTAKE", new String[] {
            "ID", "ACTOR_ID", "OPERATION_KEY", "FILE_NAME", "MEDIA_TYPE", "FILE_SIZE",
            "SHA256", "STORAGE_KEY", "STATUS", "LEASE_TOKEN", "LEASE_UNTIL", "FIELDS_JSON",
            "PARSE_METHOD", "ERROR_CODE", "CREATED_AT", "UPDATED_AT", "PROJECT_NAME",
            "PROJECT_NO", "DEPARTMENT_NAME", "PROCUREMENT_TYPE", "BUDGET_AMOUNT",
            "SUBJECT_CODE", "SUBJECT_NAME", "PROCUREMENT_METHOD", "REQUIREMENT_DESCRIPTION", "APPLICANT_NAME"});
        COLUMNS.put("PROCUREMENT_MATERIAL_LINK", new String[] {"DEMAND_ID", "MATERIAL_ID", "CREATED_AT"});
        COLUMNS.put("PROCUREMENT_DEMAND_MATERIAL", new String[] {
            "DEMAND_ID", "MATERIAL_ID", "MATERIAL_ORDER", "IS_PRIMARY", "CREATED_AT"});
        COLUMNS.put("PROCUREMENT_DEMAND", new String[] {"ID", "DELETED", "SOURCE_PROJECT_ID"});
    }
    static final Set<String> STATUSES = Set.of("PROCESSING", "PARSED", "FAILED", "UNKNOWN");
    static final Set<String> CODES = Set.of(
        "AI_EXTRACTION_UNAVAILABLE", "AI_RESULT_INVALID", "AI_NO_FIELDS", "AI_EVIDENCE_INVALID",
        "OCR_UNAVAILABLE", "DOCUMENT_TEXT_EMPTY", "DOCUMENT_READ_FAILED", "DOCUMENT_CONTAINER_INVALID",
        "PDF_PARSE_FAILED", "PAGE_LIMIT_EXCEEDED", "TEXT_LIMIT_EXCEEDED", "SOURCE_FILE_MISSING",
        "SOURCE_FILE_MISMATCH", "STORAGE_OUTCOME_UNKNOWN", "MULTIPLE_PROJECTS_REQUIRE_SEPARATE_FILES");
    static void emit(String key, String value) { System.out.println("DBCHECK_" + key + "=" + value); }
    static String safe(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : "OTHER";
    }
    static boolean visible(Connection c, String owner, String table) {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME=?")) {
            s.setString(1, owner); s.setString(2, table);
            try (ResultSet r = s.executeQuery()) { return r.next() && r.getLong(1) > 0; }
        } catch (SQLException e) { return false; }
    }
    static String accessible(Connection c, String table) {
        try (Statement s = c.createStatement()) {
            s.setQueryTimeout(12);
            try (ResultSet ignored = s.executeQuery("SELECT 1 FROM AUDIT_APP." + table + " WHERE 1=0")) {
                return "OK";
            }
        } catch (SQLException e) { return "UNKNOWN_OR_INACCESSIBLE"; }
    }
    static void columns(Connection c, String table, String owner) {
        Set<String> found = new HashSet<>();
        try (PreparedStatement s = c.prepareStatement(
                "SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=?")) {
            s.setString(1, owner); s.setString(2, table);
            try (ResultSet r = s.executeQuery()) { while (r.next()) found.add(r.getString(1)); }
            if (found.isEmpty()) { emit("COLUMNS_" + table, "UNKNOWN_OR_INACCESSIBLE"); return; }
            int missing = 0;
            for (String column : COLUMNS.get(table)) {
                if (!found.contains(column)) { emit("MISSING_COLUMN_" + table + "_" + column, "YES"); missing++; }
            }
            emit("COLUMNS_" + table, missing == 0 ? "OK" : "MISSING");
        } catch (SQLException e) { emit("COLUMNS_" + table, "UNKNOWN_OR_INACCESSIBLE"); }
    }
    static void synonym(Connection c) {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT TABLE_OWNER,TABLE_NAME FROM ALL_SYNONYMS WHERE OWNER=? AND SYNONYM_NAME=?")) {
            s.setString(1, APP); s.setString(2, "PROCUREMENT_DEMAND");
            boolean target = false;
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) if ("SYSDBA".equals(r.getString(1)) &&
                        "PROCUREMENT_DEMAND".equals(r.getString(2))) target = true;
            }
            emit("PARENT_SYNONYM", target ? "OK" : "NOT_VISIBLE_OR_DIFFERENT");
        } catch (SQLException e) { emit("PARENT_SYNONYM", "UNKNOWN_OR_INACCESSIBLE"); }
    }
    static void fk(Connection c, String table) {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT COUNT(*) FROM ALL_CONSTRAINTS C JOIN ALL_CONSTRAINTS P " +
                "ON P.OWNER=C.R_OWNER AND P.CONSTRAINT_NAME=C.R_CONSTRAINT_NAME " +
                "WHERE C.OWNER=? AND C.TABLE_NAME=? AND C.CONSTRAINT_TYPE='R' " +
                "AND C.STATUS='ENABLED' AND P.OWNER=? AND P.TABLE_NAME='BUSINESS_MATERIAL_INTAKE'")) {
            s.setString(1, APP); s.setString(2, table); s.setString(3, APP);
            try (ResultSet r = s.executeQuery()) {
                emit("MATERIAL_FK_" + table, r.next() && r.getLong(1) > 0 ? "OK" : "NOT_VISIBLE_OR_MISSING");
            }
        } catch (SQLException e) { emit("MATERIAL_FK_" + table, "UNKNOWN_OR_INACCESSIBLE"); }
    }
    static void recent(Connection c) {
        Map<String, Long> statuses = new TreeMap<>(), codes = new TreeMap<>();
        try (Statement s = c.createStatement()) {
            s.setQueryTimeout(20);
            try (ResultSet r = s.executeQuery(
                    "SELECT STATUS,ERROR_CODE,COUNT(*) FROM AUDIT_APP.BUSINESS_MATERIAL_INTAKE " +
                    "WHERE UPDATED_AT >= SYSDATE - 7 GROUP BY STATUS,ERROR_CODE")) {
                while (r.next()) {
                    String status = safe(r.getString(1), STATUSES);
                    long count = r.getLong(3);
                    statuses.merge(status, count, Long::sum);
                    if ("FAILED".equals(status)) codes.merge(safe(r.getString(2), CODES), count, Long::sum);
                }
            }
            emit("RECENT_7D_AGGREGATE", "OK");
            for (String status : List.of("PROCESSING", "PARSED", "FAILED", "UNKNOWN", "OTHER"))
                emit("STATUS_" + status, Long.toString(statuses.getOrDefault(status, 0L)));
            for (String code : new TreeSet<>(CODES))
                emit("FAILED_" + code, Long.toString(codes.getOrDefault(code, 0L)));
            emit("FAILED_OTHER", Long.toString(codes.getOrDefault("OTHER", 0L)));
        } catch (SQLException e) { emit("RECENT_7D_AGGREGATE", "UNKNOWN_OR_INACCESSIBLE"); }
    }
    public static void main(String[] args) {
        try {
            DriverManager.setLoginTimeout(8);
            Class.forName("dm.jdbc.driver.DmDriver");
            try (Connection c = DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),
                    System.getenv("AUDIT_DB_USERNAME"), System.getenv("AUDIT_DB_PASSWORD"))) {
                emit("CONNECTION", "OK");
                try { c.setReadOnly(true); } catch (SQLException ignored) { /* SELECT-only code. */ }
                for (String table : COLUMNS.keySet()) {
                    String owner = "PROCUREMENT_DEMAND".equals(table) ? "SYSDBA" : APP;
                    emit("TABLE_" + owner + "_" + table,
                        visible(c, owner, table) ? "VISIBLE" : "NOT_VISIBLE_OR_MISSING");
                    emit("ACCESS_" + table, accessible(c, table));
                    columns(c, table, owner);
                }
                synonym(c);
                fk(c, "PROCUREMENT_MATERIAL_LINK");
                fk(c, "PROCUREMENT_DEMAND_MATERIAL");
                recent(c);
            }
        } catch (Throwable ignored) {
            // JDBC and driver errors can embed private connection details.
            emit("CONNECTION", "UNKNOWN_OR_FAILED");
        }
    }
}
JAVA

# Driver output is private; only fixed result tokens are copied to the report.
if timeout 90s "$java_bin" -cp "$work/$driver" "$work/AuditDbDiagnose.java" \
    > "$work/jvm.out" 2> "$work/jvm.err"; then
  grep -E '^DBCHECK_[A-Z0-9_]+=(OK|VISIBLE|MISSING|YES|NOT_VISIBLE_OR_MISSING|NOT_VISIBLE_OR_DIFFERENT|UNKNOWN_OR_INACCESSIBLE|UNKNOWN_OR_FAILED|[0-9]+)$' \
    "$work/jvm.out" >> "$report" || true
else
  printf 'JDBC_EXECUTION=UNKNOWN_OR_FAILED\n' >> "$report"
fi
if ! grep -qx 'DBCHECK_CONNECTION=OK' "$report"; then
  printf 'RESULT=INCONCLUSIVE\n' >> "$report"
elif ! grep -q '^DBCHECK_RECENT_7D_AGGREGATE=' "$report"; then
  printf 'RESULT=INCOMPLETE\n' >> "$report"
else
  printf 'RESULT=CHECK_REPORT\n' >> "$report"
fi
echo "Report: $report"
echo 'Send only the report; do not send backend.env or raw service logs.'
