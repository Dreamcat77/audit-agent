package com.fuquan.audit.integration.dm8;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.regex.*;

/** Read-only, fail-closed proof for exactly the two semantic checkpoint tables. */
public final class SemanticCheckpointSchemaPreflight {
    private static final String PLAN = "CONTRACT_SEMANTIC_PLAN", UNIT = "CONTRACT_SEMANTIC_UNIT";
    private static final String TWO = "('CONTRACT_SEMANTIC_PLAN','CONTRACT_SEMANTIC_UNIT')";
    private static final String FIVE = "('CONTRACT_SEMANTIC_PLAN','CONTRACT_SEMANTIC_UNIT','CONTRACT_REVIEW_RUN','CONTRACT_REVIEW_STEP','CONTRACT_REVIEW_STEP_ATTEMPT')";
    private final JdbcTemplate jdbc;
    public SemanticCheckpointSchemaPreflight(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }
    public void verify() {
        String classification;
        String mismatch;
        try {
            var tables = jdbc.queryForList("SELECT TABLE_NAME FROM USER_TABLES WHERE TABLE_NAME IN " + TWO);
            var columns = jdbc.queryForList("SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE,DATA_LENGTH,NULLABLE FROM USER_TAB_COLUMNS WHERE TABLE_NAME IN " + TWO);
            var constraints = jdbc.queryForList("SELECT C.TABLE_NAME,C.CONSTRAINT_NAME,C.CONSTRAINT_TYPE,C.STATUS,C.SEARCH_CONDITION,C.DELETE_RULE,C.R_CONSTRAINT_NAME,R.TABLE_NAME AS REFERENCED_TABLE,R.STATUS AS REFERENCED_STATUS,R.CONSTRAINT_TYPE AS REFERENCED_TYPE,CASE WHEN C.R_OWNER=USER THEN 1 ELSE 0 END AS SAME_OWNER FROM USER_CONSTRAINTS C LEFT JOIN USER_CONSTRAINTS R ON R.CONSTRAINT_NAME=C.R_CONSTRAINT_NAME WHERE C.TABLE_NAME IN " + TWO);
            var constraintColumns = jdbc.queryForList("SELECT CONSTRAINT_NAME,COLUMN_NAME,POSITION FROM USER_CONS_COLUMNS WHERE TABLE_NAME IN " + FIVE);
            var indexes = jdbc.queryForList("SELECT TABLE_NAME,INDEX_NAME,UNIQUENESS,STATUS FROM USER_INDEXES WHERE TABLE_NAME IN " + FIVE);
            var indexColumns = jdbc.queryForList("SELECT INDEX_NAME,COLUMN_NAME,COLUMN_POSITION FROM USER_IND_COLUMNS WHERE TABLE_NAME IN " + FIVE);
            Map<String, String> expected = columns(); Map<String, String> observed = new HashMap<>();
            for (String table : List.of(PLAN, UNIT)) {
                expected.put("table:" + table, "PRESENT");
                if (tables.stream().filter(r -> table.equals(value(r, "TABLE_NAME"))).count() == 1)
                    observed.put("table:" + table, "PRESENT");
            }
            for (var column : columns) {
                String key = "column:" + value(column, "TABLE_NAME") + "." + value(column, "COLUMN_NAME");
                String signature = value(column, "DATA_TYPE") + ":" + value(column, "DATA_LENGTH") + ":" + value(column, "NULLABLE");
                if (observed.putIfAbsent(key, signature) != null) observed.put(key, "AMBIGUOUS");
            }
            for (Constraint required : constraints()) {
                String key = "constraint:" + required.name;
                expected.put(key, required.signature());
                var found = constraints.stream().filter(c -> required.name.equals(value(c, "CONSTRAINT_NAME"))
                        && required.table.equals(value(c, "TABLE_NAME"))).toList();
                if (found.size() == 1) {
                    var actual = found.get(0); String type = value(actual, "CONSTRAINT_TYPE");
                    // Real DM8 lists CHECK columns with POSITION=NULL. The exact
                    // parsed expression proves its columns; only keys have order.
                    List<String> ownColumns = type.equals("C") ? List.of()
                            : ordered(constraintColumns, "CONSTRAINT_NAME", required.name, "POSITION");
                    String extra = "";
                    if (type.equals("C")) extra = condition(value(actual, "SEARCH_CONDITION"));
                    if (type.equals("R")) extra = value(actual, "REFERENCED_TABLE") + ":"
                            + String.join(",", ordered(constraintColumns, "CONSTRAINT_NAME", value(actual, "R_CONSTRAINT_NAME"), "POSITION"))
                            + ":" + value(actual, "REFERENCED_STATUS") + ":" + value(actual, "REFERENCED_TYPE")
                            + ":" + value(actual, "SAME_OWNER") + ":" + value(actual, "DELETE_RULE");
                    observed.put(key, type + ":" + value(actual, "STATUS") + ":" + String.join(",", ownColumns) + ":" + extra);
                }
                if (required.type.equals("P") || required.type.equals("U")) {
                    expected.put("key-index:" + required.name, "VALID");
                    if (validUniqueIndex(indexes, indexColumns, required.table, required.columns))
                        observed.put("key-index:" + required.name, "VALID");
                }
                if (required.type.equals("R")) {
                    expected.put("parent-index:" + required.name, "VALID");
                    if (found.size() == 1 && validUniqueIndex(indexes, indexColumns, required.parent, required.parentColumns))
                        observed.put("parent-index:" + required.name, "VALID");
                }
            }
            String indexName = "IX_SEMANTIC_UNIT_PLAN";
            expected.put("index:" + indexName, UNIT + ":NONUNIQUE:VALID:PLAN_ID,UNIT_ORDINAL,EXECUTION_NO");
            var index = indexes.stream().filter(i -> indexName.equals(value(i, "INDEX_NAME"))).toList();
            if (index.size() == 1) observed.put("index:" + indexName, value(index.get(0), "TABLE_NAME") + ":"
                    + value(index.get(0), "UNIQUENESS") + ":" + value(index.get(0), "STATUS") + ":"
                    + String.join(",", ordered(indexColumns, "INDEX_NAME", indexName, "COLUMN_POSITION")));
            classification = ContractReviewCloseoutSchemaPreflight.classify(expected, observed, true);
            mismatch = expected.keySet().stream().sorted().filter(key -> !expected.get(key).equals(observed.get(key)))
                    .findFirst().orElse("");
        } catch (RuntimeException unproven) {
            // Driver exceptions can contain account/URL details. Do not expose them.
            throw failure("UNKNOWN");
        }
        if (!classification.equals("ALREADY_MATCHING")) throw failure(classification + " at " + mismatch);
    }
    private static Map<String, String> columns() {
        Map<String, String> expected = new HashMap<>();
        add(expected, PLAN, "VARCHAR", 36, "N", "PLAN_ID,RUN_ID,STEP_ID,ORIGINAL_ATTEMPT_ID,CURRENT_ATTEMPT_ID");
        add(expected, PLAN, "VARCHAR", 64, "N", "INPUT_FINGERPRINT,POLICY_FINGERPRINT");
        add(expected, PLAN, "VARCHAR", 64, "Y", "FINAL_RESULT_SHA256");
        add(expected, PLAN, "VARCHAR", 128, "N", "OWNER"); add(expected, PLAN, "VARCHAR", 16, "N", "STATUS");
        add(expected, PLAN, "CLOB", Integer.MAX_VALUE, "N", "PLAN_JSON");
        add(expected, PLAN, "TIMESTAMP", 8, "N", "DEADLINE_AT,LEASE_UNTIL,CREATED_AT,UPDATED_AT");
        add(expected, PLAN, "BIGINT", 8, "N", "TIME_LIMIT_MS,FENCE,USED_MS,RESERVED_MS,VERSION_NO");
        add(expected, PLAN, "INTEGER", 4, "N", "CALL_LIMIT,REPAIR_LIMIT,USED_CALLS,RESERVED_CALLS,USED_REPAIRS,RESERVED_REPAIRS");
        add(expected, UNIT, "VARCHAR", 36, "N", "UNIT_EXECUTION_ID,PLAN_ID,ATTEMPT_ID");
        add(expected, UNIT, "VARCHAR", 64, "N", "LOGICAL_UNIT_KEY,INPUT_FINGERPRINT");
        add(expected, UNIT, "VARCHAR", 36, "Y", "CALL_AUDIT_ID");
        add(expected, UNIT, "VARCHAR", 64, "Y", "RESULT_SHA256,SAFE_REASON,REQUEST_FINGERPRINT");
        add(expected, UNIT, "VARCHAR", 128, "Y", "PROVIDER"); add(expected, UNIT, "VARCHAR", 200, "Y", "MODEL_NAME");
        add(expected, UNIT, "VARCHAR", 128, "N", "OWNER"); add(expected, UNIT, "VARCHAR", 24, "N", "STATUS");
        add(expected, UNIT, "VARCHAR", 16, "N", "PURPOSE");
        add(expected, UNIT, "CLOB", Integer.MAX_VALUE, "N", "DEPENDENCIES_JSON");
        add(expected, UNIT, "CLOB", Integer.MAX_VALUE, "Y", "RESULT_JSON");
        add(expected, UNIT, "TIMESTAMP", 8, "N", "LEASE_UNTIL,CREATED_AT,UPDATED_AT");
        add(expected, UNIT, "BIGINT", 8, "N", "FENCE,RESERVED_MS,DURATION_MS,VERSION_NO");
        add(expected, UNIT, "INTEGER", 4, "N", "EXECUTION_NO,UNIT_ORDINAL,CALL_ORDINAL");
        return expected;
    }
    private static void add(Map<String, String> map, String table, String type, int width, String nullable, String columns) {
        for (String column : columns.split(",")) map.put("column:" + table + "." + column, type + ":" + width + ":" + nullable);
    }
    private record Constraint(String table, String name, String type, List<String> columns,
            String parent, List<String> parentColumns, String check) {
        String signature() { return type + ":ENABLED:" + String.join(",", columns) + ":" + (type.equals("C") ? condition(check)
                : type.equals("R") ? parent + ":" + String.join(",", parentColumns) + ":ENABLED:P:1:NO ACTION" : ""); }
    }
    private static Constraint key(String table, String name, String type, String columns) {
        return new Constraint(table, name, type, List.of(columns.split(",")), "", List.of(), "");
    }
    private static Constraint fk(String table, String name, String column, String parent, String parentColumn) {
        return new Constraint(table, name, "R", List.of(column), parent, List.of(parentColumn), "");
    }
    private static Constraint check(String table, String name, String expression) {
        return new Constraint(table, name, "C", List.of(), "", List.of(), expression);
    }
    private static List<Constraint> constraints() {
        return List.of(key(PLAN, "PK_CONTRACT_SEMANTIC_PLAN", "P", "PLAN_ID"), key(PLAN, "UK_SEMANTIC_PLAN_STEP", "U", "STEP_ID"),
            fk(PLAN,"FK_SEM_PLAN_RUN","RUN_ID","CONTRACT_REVIEW_RUN","RUN_ID"),
            fk(PLAN,"FK_SEM_PLAN_STEP","STEP_ID","CONTRACT_REVIEW_STEP","STEP_ID"),
            fk(PLAN,"FK_SEM_PLAN_ORIGINAL","ORIGINAL_ATTEMPT_ID","CONTRACT_REVIEW_STEP_ATTEMPT","ATTEMPT_ID"),
            fk(PLAN,"FK_SEM_PLAN_CURRENT","CURRENT_ATTEMPT_ID","CONTRACT_REVIEW_STEP_ATTEMPT","ATTEMPT_ID"),
            check(PLAN,"CK_SEM_PLAN_STATUS","STATUS IN ('ACTIVE','COMPLETE')"),
            check(PLAN,"CK_SEM_PLAN_BUDGET","TIME_LIMIT_MS > 0 AND CALL_LIMIT > 0 AND REPAIR_LIMIT >= 0 AND REPAIR_LIMIT <= CALL_LIMIT AND USED_CALLS >= 0 AND RESERVED_CALLS >= 0 AND USED_CALLS + RESERVED_CALLS <= CALL_LIMIT AND USED_REPAIRS >= 0 AND RESERVED_REPAIRS >= 0 AND USED_REPAIRS + RESERVED_REPAIRS <= REPAIR_LIMIT AND USED_MS >= 0 AND RESERVED_MS >= 0 AND FENCE > 0 AND VERSION_NO >= 0"),
            check(PLAN,"CK_SEM_PLAN_FINAL","STATUS <> 'COMPLETE' OR FINAL_RESULT_SHA256 IS NOT NULL"),
            key(UNIT,"PK_CONTRACT_SEMANTIC_UNIT","P","UNIT_EXECUTION_ID"),
            key(UNIT,"UK_SEMANTIC_UNIT_EXEC","U","PLAN_ID,LOGICAL_UNIT_KEY,EXECUTION_NO"),
            key(UNIT,"UK_SEMANTIC_CALL_AUDIT","U","CALL_AUDIT_ID"),
            fk(UNIT,"FK_SEM_UNIT_PLAN","PLAN_ID",PLAN,"PLAN_ID"),
            fk(UNIT,"FK_SEM_UNIT_ATTEMPT","ATTEMPT_ID","CONTRACT_REVIEW_STEP_ATTEMPT","ATTEMPT_ID"),
            check(UNIT,"CK_SEM_UNIT_PURPOSE","PURPOSE IN ('ANALYZE','REPAIR','SYNTHESIZE')"),
            check(UNIT,"CK_SEM_UNIT_STATUS","STATUS IN ('PLANNED','CLAIMED','SENT','VERIFIED','REPAIR_REQUIRED','FAILED','UNKNOWN')"),
            check(UNIT,"CK_SEM_UNIT_COUNTER","EXECUTION_NO > 0 AND UNIT_ORDINAL > 0 AND FENCE > 0 AND RESERVED_MS >= 0 AND DURATION_MS >= 0 AND VERSION_NO >= 0"),
            check(UNIT,"CK_SEM_UNIT_RESULT","STATUS <> 'VERIFIED' OR (RESULT_JSON IS NOT NULL AND RESULT_SHA256 IS NOT NULL)"),
            check(UNIT,"CK_SEM_UNIT_CALL","STATUS IN ('PLANNED','CLAIMED') OR (CALL_AUDIT_ID IS NOT NULL AND PROVIDER IS NOT NULL AND MODEL_NAME IS NOT NULL AND REQUEST_FINGERPRINT IS NOT NULL AND CALL_ORDINAL > 0)"));
    }
    private static boolean validUniqueIndex(List<Map<String, Object>> indexes, List<Map<String, Object>> columns, String table, List<String> required) {
        return indexes.stream().anyMatch(i -> table.equals(value(i, "TABLE_NAME")) && "UNIQUE".equals(value(i,"UNIQUENESS"))
                && "VALID".equals(value(i,"STATUS")) && required.equals(ordered(columns,"INDEX_NAME",value(i,"INDEX_NAME"),"COLUMN_POSITION")));
    }
    private static List<String> ordered(List<Map<String, Object>> rows, String key, String id, String ordinal) {
        return rows.stream().filter(c -> id.equals(value(c,key)))
                .sorted(Comparator.comparingInt(c -> Integer.parseInt(value(c,ordinal))))
                .map(c -> value(c,"COLUMN_NAME")).toList();
    }
    private static String value(Map<String, Object> row, String key) { Object value = row.get(key); return value == null ? "" : value.toString(); }
    private static IllegalStateException failure(String classification) {
        return new IllegalStateException("required semantic checkpoint schema is missing or drifted: " + classification);
    }

    /** Only the boolean/comparison/IN/addition grammar used by these fixed CHECKs. */
    private static String condition(String source) { return new Check(source).parse(); }
    private static final class Check {
        final List<String> tokens = new ArrayList<>(); int position, depth;
        Check(String source) {
            if (source == null || source.length() > 32768) throw new IllegalArgumentException("unsupported CHECK");
            // Identifier quoting is display syntax; quotes inside SQL string
            // literals are data and must never be discarded during comparison.
            Matcher matcher = Pattern.compile("'(?:''|[^'])*'|\"[A-Za-z_][A-Za-z0-9_]*\"|[A-Za-z_][A-Za-z0-9_]*|[0-9]+|<>|!=|<=|>=|[=<>+(),]").matcher(source);
            String text = source; int end = 0;
            while (matcher.find()) { if (!text.substring(end, matcher.start()).isBlank()) throw new IllegalArgumentException("unsupported CHECK");
                String token = matcher.group();
                if (token.startsWith("\"")) {
                    token = token.substring(1, token.length() - 1);
                    if (!token.matches("[A-Z_][A-Z0-9_]*")) throw new IllegalArgumentException("unsupported quoted CHECK identity");
                }
                tokens.add(token.startsWith("'") ? token : token.equals("!=") ? "<>" : token.toUpperCase(Locale.ROOT)); end = matcher.end(); }
            if (!text.substring(end).isBlank()) throw new IllegalArgumentException("unsupported CHECK");
        }
        String parse() { String result = or(); if (position != tokens.size()) throw new IllegalArgumentException("unreadable CHECK"); return result; }
        String or() { List<String> items = new ArrayList<>(); items.add(and()); while (eat("OR")) items.add(and()); return join("OR", items); }
        String and() { List<String> items = new ArrayList<>(); items.add(compare()); while (eat("AND")) items.add(compare()); return join("AND", items); }
        String compare() {
            if (eat("NOT")) {
                if (++depth > 128) throw new IllegalArgumentException("unsupported CHECK depth");
                require("("); String inner = or(); require(")"); depth--;
                // Only DM8's display alias for a single identifier IS NOT NULL.
                // Never broaden this to arbitrary negation or De Morgan rewrites.
                if (!inner.matches("NULL\\([A-Z_][A-Z0-9_]*\\)")) throw new IllegalArgumentException("unsupported CHECK negation");
                return "NOT" + inner;
            }
            String left = add();
            if (eat("IS")) { boolean not = eat("NOT"); require("NULL"); return (not ? "NOTNULL(" : "NULL(") + left + ")"; }
            if (eat("IN")) { require("("); List<String> values = new ArrayList<>(); values.add(add());
                while (eat(",")) values.add(add()); require(")"); return "IN(" + left + "," + String.join(",", values) + ")"; }
            if (position < tokens.size() && Set.of("=","<>","<",">","<=",">=").contains(tokens.get(position))) {
                String op = tokens.get(position++); return op + "(" + left + "," + add() + ")"; }
            return left;
        }
        String add() { String result = primary(); while (eat("+")) result = "+(" + result + "," + primary() + ")"; return result; }
        String primary() { if (eat("(")) { if (++depth > 128) throw new IllegalArgumentException("unsupported CHECK depth");
                String result = or(); require(")"); depth--; return result; }
            if (position >= tokens.size()) throw new IllegalArgumentException("incomplete CHECK");
            String token = tokens.get(position++);
            if (!token.startsWith("'") && !token.matches("[A-Z_][A-Z0-9_]*|[0-9]+")) throw new IllegalArgumentException("invalid CHECK atom");
            return token; }
        boolean eat(String token) { if (position < tokens.size() && tokens.get(position).equals(token)) { position++; return true; } return false; }
        void require(String token) { if (!eat(token)) throw new IllegalArgumentException("incomplete CHECK"); }
        static String join(String op, List<String> items) {
            if (items.size() == 1) return items.get(0);
            return op + "(" + String.join(",", items.stream().map(item -> item.startsWith(op + "(")
                    ? item.substring(op.length() + 1, item.length() - 1) : item).toList()) + ")";
        }
    }
}
