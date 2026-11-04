package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.application.reviewrun.semantic.*;
import com.fuquan.audit.contract.application.reviewrun.port.*;
import com.fuquan.audit.contract.application.project.factai.ContractModelCallAuditRepository;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

class SemanticCheckpointConfigurationTest {
    @Test void defaultAndExplicitFalseNeverRegisterCheckpointExecutionOrReadCatalog() {
        for (String flag : List.of("absent", "false")) {
            var jdbc = mock(JdbcTemplate.class); var runner = runner(jdbc, "local-dev");
            if (flag.equals("false")) runner = runner.withPropertyValues("audit.contract.semantic-checkpoints.enabled=false");
            runner.run(context -> {
                assertThat(context).doesNotHaveBean(SemanticCheckpointCoordinator.class);
                assertThat(context).doesNotHaveBean(DurableSemanticExecution.class);
                verify(jdbc, never()).queryForList(anyString());
            });
        }
    }
    @Test void enablingLocalMemoryProfileCannotSilentlyRegisterDm8Execution() {
        var jdbc = mock(JdbcTemplate.class);
        runner(jdbc, "local").withPropertyValues("audit.contract.semantic-checkpoints.enabled=true").run(context -> {
            assertThat(context).doesNotHaveBean(DurableSemanticExecution.class);
            verify(jdbc, never()).queryForList(anyString());
        });
    }
    @Test void explicitFlagAndCompleteCatalogRegisterBothBeansOnEveryRealProfile() throws Exception {
        for (String profile : List.of("local-dev", "dev29", "customer")) {
            var jdbc = catalog().jdbc();
            runner(jdbc, profile).withPropertyValues("audit.contract.semantic-checkpoints.enabled=true").run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(SemanticCheckpointCoordinator.class);
                assertThat(context).hasSingleBean(DurableSemanticExecution.class);
            });
        }
    }
    @Test void missingTablesIncorrectParentFkOrDisabledCheckRejectStartup() throws Exception {
        for (String defect : List.of("missing", "fk", "disabled-check")) {
            var data = catalog();
            if (defect.equals("missing")) { data.tables.clear(); data.columns.clear(); data.constraints.clear();
                data.constraintColumns.clear(); data.indexes.clear(); data.indexColumns.clear(); }
            if (defect.equals("fk")) data.named("FK_SEM_PLAN_CURRENT").put("REFERENCED_TABLE", "CONTRACT_REVIEW_RUN");
            if (defect.equals("disabled-check")) data.named("CK_SEM_PLAN_BUDGET").put("STATUS", "DISABLED");
            runner(data.jdbc(), "local-dev").withPropertyValues("audit.contract.semantic-checkpoints.enabled=true").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("semantic checkpoint schema");
            });
        }
    }
    @Test void wrongWidthCheckExpressionUniqueIndexOrUnknownCatalogAlsoFailsClosed() throws Exception {
        for (String defect : List.of("width", "condition", "index", "unreadable")) {
            var data = catalog();
            if (defect.equals("width")) data.columns.stream().filter(c -> c.get("COLUMN_NAME").equals("INPUT_FINGERPRINT"))
                    .findFirst().orElseThrow().put("DATA_LENGTH", 36);
            if (defect.equals("condition")) data.named("CK_SEM_PLAN_BUDGET").put("SEARCH_CONDITION", "1=1");
            if (defect.equals("index")) data.indexes.stream().filter(i -> i.get("INDEX_NAME").equals("IX_SEMANTIC_UNIT_PLAN"))
                    .findFirst().orElseThrow().put("STATUS", "UNUSABLE");
            JdbcTemplate jdbc = data.jdbc();
            if (defect.equals("unreadable")) doThrow(new IllegalStateException("synthetic-secret")).when(jdbc).queryForList(anyString());
            assertThatThrownBy(() -> new SemanticCheckpointSchemaPreflight(jdbc).verify())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("semantic checkpoint schema")
                    .hasMessageNotContaining("synthetic-secret");
        }
    }
    @Test void catalogFormattingIsAcceptedButDifferentBooleanGroupingCannotHideCheckDrift() throws Exception {
        var formatted = catalog();
        formatted.constraints.stream().filter(c -> c.get("CONSTRAINT_TYPE").equals("C"))
                .forEach(c -> c.put("SEARCH_CONDITION", "((" + c.get("SEARCH_CONDITION") + "))"));
        new SemanticCheckpointSchemaPreflight(formatted.jdbc()).verify();
        var drifted = catalog();
        drifted.named("CK_SEM_UNIT_RESULT").put("SEARCH_CONDITION",
                "(STATUS <> 'VERIFIED' OR RESULT_JSON IS NOT NULL) AND RESULT_SHA256 IS NOT NULL");
        assertThatThrownBy(() -> new SemanticCheckpointSchemaPreflight(drifted.jdbc()).verify())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CK_SEM_UNIT_RESULT");
        var literalDrift = catalog();
        literalDrift.named("CK_SEM_UNIT_STATUS").put("SEARCH_CONDITION",
                literalDrift.named("CK_SEM_UNIT_STATUS").get("SEARCH_CONDITION").toString().replace("'VERIFIED'", "'VERI\"FIED'"));
        assertThatThrownBy(() -> new SemanticCheckpointSchemaPreflight(literalDrift.jdbc()).verify())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CK_SEM_UNIT_STATUS");
    }
    @Test void existingParentIndexesDoNotTurnAbsentNewTablesIntoPartialMigration() throws Exception {
        var data = catalog();
        data.tables.clear(); data.columns.clear(); data.constraints.clear(); data.constraintColumns.clear();
        data.indexes.removeIf(index -> index.get("TABLE_NAME").toString().startsWith("CONTRACT_SEMANTIC_"));
        assertThat(data.indexes).isNotEmpty();
        assertThatThrownBy(() -> new SemanticCheckpointSchemaPreflight(data.jdbc()).verify())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("NOT_APPLIED");
    }

    @Test void checkFormattingCannotChangeQuotedIdentityOrLiteralData() throws Exception {
        var uppercaseQuoted = catalog();
        var original = uppercaseQuoted.named("CK_SEM_UNIT_STATUS").get("SEARCH_CONDITION").toString();
        uppercaseQuoted.named("CK_SEM_UNIT_STATUS").put("SEARCH_CONDITION", original.replace("STATUS", "\"STATUS\""));
        new SemanticCheckpointSchemaPreflight(uppercaseQuoted.jdbc()).verify();
        for (String changed : List.of(original.replace("STATUS", "\"status\""),
                original.replace("'VERIFIED'", "'verified'"),
                original.replace("'VERIFIED'", "'VERI FIED'"),
                original.replace("'VERIFIED'", "'VERI''FIED'"))) {
            var data = catalog();
            data.named("CK_SEM_UNIT_STATUS").put("SEARCH_CONDITION", changed);
            assertThatThrownBy(() -> new SemanticCheckpointSchemaPreflight(data.jdbc()).verify())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("semantic checkpoint schema");
        }
    }

    @Test void realDm8NotEqualAndNegatedNullDisplayPassPreflightWithoutChangingTheRequiredCheck() throws Exception {
        var notEqualOnly = catalog();
        notEqualOnly.named("CK_SEM_PLAN_FINAL").put("SEARCH_CONDITION", "STATUS != 'COMPLETE' OR FINAL_RESULT_SHA256 IS NOT NULL");
        new SemanticCheckpointSchemaPreflight(notEqualOnly.jdbc()).verify();
        var actual = catalog();
        actual.named("CK_SEM_PLAN_FINAL").put("SEARCH_CONDITION", " (STATUS != 'COMPLETE' OR  NOT (FINAL_RESULT_SHA256 IS NULL ) )");
        actual.named("CK_SEM_UNIT_RESULT").put("SEARCH_CONDITION", "STATUS != 'VERIFIED' OR (NOT (RESULT_JSON IS NULL) AND NOT (RESULT_SHA256 IS NULL))");
        actual.named("CK_SEM_UNIT_CALL").put("SEARCH_CONDITION", "STATUS IN ('PLANNED','CLAIMED') OR (NOT (CALL_AUDIT_ID IS NULL) AND NOT (PROVIDER IS NULL) AND NOT (MODEL_NAME IS NULL) AND NOT (REQUEST_FINGERPRINT IS NULL) AND CALL_ORDINAL > 0)");
        new SemanticCheckpointSchemaPreflight(actual.jdbc()).verify();
    }

    @Test void negatedNullDisplayCannotWeakenChecksOrNormalizeAwayLiteralAndIdentityDrift() throws Exception {
        for (String altered : List.of(
                "STATUS != 'complete' OR NOT (FINAL_RESULT_SHA256 IS NULL)",
                "STATUS != 'COM PLETE' OR NOT (FINAL_RESULT_SHA256 IS NULL)",
                "STATUS != 'COM''PLETE' OR NOT (FINAL_RESULT_SHA256 IS NULL)",
                "STATUS != 'COMPLETE' OR FINAL_RESULT_SHA256 IS NULL",
                "STATUS != 'COMPLETE' OR NOT (FINAL_RESULT_SHA256 IS NOT NULL)",
                "STATUS != 'COMPLETE' AND NOT (FINAL_RESULT_SHA256 IS NULL)",
                "STATUS != 'COMPLETE' OR NOT (FINAL_RESULT_SHA256 IS NULL OR STATUS = 'COMPLETE')",
                "NOT (STATUS != 'COMPLETE' OR FINAL_RESULT_SHA256 IS NULL)",
                "STATUS != 'COMPLETE' OR NOT (FINAL_RESULT_SHA256 IS NULL) OR 1=1",
                "STATUS != 'COMPLETE' OR NOT (\"final_result_sha256\" IS NULL)",
                "STATUS != 'COMPLETE' OR NOT (FINAL_RESULT_SHA256 IS NULL); UNKNOWN")) {
            var data = catalog(); data.named("CK_SEM_PLAN_FINAL").put("SEARCH_CONDITION", altered);
            assertThatThrownBy(() -> new SemanticCheckpointSchemaPreflight(data.jdbc()).verify())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("semantic checkpoint schema");
        }
        for (String altered : List.of(
                "(STATUS != 'VERIFIED' OR NOT (RESULT_JSON IS NULL)) AND NOT (RESULT_SHA256 IS NULL)",
                "STATUS != 'VERIFIED' OR NOT (RESULT_JSON IS NULL AND RESULT_SHA256 IS NULL)")) {
            var data = catalog(); data.named("CK_SEM_UNIT_RESULT").put("SEARCH_CONDITION", altered);
            assertThatThrownBy(() -> new SemanticCheckpointSchemaPreflight(data.jdbc()).verify())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("semantic checkpoint schema");
        }
    }

    static ApplicationContextRunner runner(JdbcTemplate jdbc, String profile) {
        return new ApplicationContextRunner().withUserConfiguration(SemanticCheckpointConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(SemanticCheckpointRepository.class, () -> mock(SemanticCheckpointRepository.class))
                .withBean(ContractReviewRunRepository.class, () -> mock(ContractReviewRunRepository.class))
                .withBean(ContractReviewStepRepository.class, () -> mock(ContractReviewStepRepository.class))
                .withBean(ContractReviewStepAttemptRepository.class, () -> mock(ContractReviewStepAttemptRepository.class))
                .withBean(ContractModelCallAuditRepository.class, () -> mock(ContractModelCallAuditRepository.class))
                .withBean(SemanticWorkPort.class, () -> mock(SemanticWorkPort.class))
                .withBean(ObjectMapper.class, ObjectMapper::new).withBean(Clock.class, Clock::systemUTC);
    }
    /** Independently translates the delivered DDL into mock runtime Catalog rows. */
    static Catalog catalog() throws Exception {
        String sql = Files.readString(Path.of("../db/dm8/CONTRACT__contract_semantic_checkpoints.sql"));
        Catalog data = new Catalog(); String table = null;
        for (String raw : sql.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("CREATE TABLE ")) { table = line.split(" ")[2]; data.tables.add(row("TABLE_NAME", table)); }
            Matcher column = Pattern.compile("^([A-Z_][A-Z0-9_]*) (VARCHAR\\((\\d+)\\)|BIGINT|INTEGER|TIMESTAMP|CLOB)(.*)").matcher(line);
            if (table != null && column.matches()) {
                String type = column.group(2).startsWith("VARCHAR") ? "VARCHAR" : column.group(2);
                int width = type.equals("VARCHAR") ? Integer.parseInt(column.group(3))
                        : type.equals("INTEGER") ? 4 : type.equals("CLOB") ? Integer.MAX_VALUE : 8;
                data.columns.add(row("TABLE_NAME", table, "COLUMN_NAME", column.group(1), "DATA_TYPE", type,
                        "DATA_LENGTH", width, "NULLABLE", column.group(4).contains("NOT NULL") ? "N" : "Y"));
            }
        }
        Matcher constraints = Pattern.compile("CONSTRAINT ([A-Z_]+) (PRIMARY KEY|UNIQUE|FOREIGN KEY|CHECK) \\(").matcher(sql);
        while (constraints.find()) {
            String name = constraints.group(1), kind = constraints.group(2);
            table = constraints.start() < sql.indexOf("CREATE TABLE CONTRACT_SEMANTIC_UNIT") ? "CONTRACT_SEMANTIC_PLAN" : "CONTRACT_SEMANTIC_UNIT";
            int open = constraints.end() - 1, close = matching(sql, open);
            String contents = sql.substring(open + 1, close);
            String type = Map.of("PRIMARY KEY", "P", "UNIQUE", "U", "FOREIGN KEY", "R", "CHECK", "C").get(kind);
            Map<String, Object> constraint = row("TABLE_NAME", table, "CONSTRAINT_NAME", name, "CONSTRAINT_TYPE", type,
                    "STATUS", "ENABLED", "SEARCH_CONDITION", type.equals("C") ? contents : null);
            if (!type.equals("C")) data.addColumns(data.constraintColumns, "CONSTRAINT_NAME", name, contents, "POSITION");
            if (type.equals("C")) for (var column : data.columns) {
                String columnName = column.get("COLUMN_NAME").toString();
                if (table.equals(column.get("TABLE_NAME")) && Pattern.compile("\\b" + columnName + "\\b").matcher(contents).find())
                    data.constraintColumns.add(row("CONSTRAINT_NAME", name, "COLUMN_NAME", columnName, "POSITION", null));
            }
            if (type.equals("R")) {
                Matcher ref = Pattern.compile("\\s*REFERENCES ([A-Z_]+)\\(([^)]+)\\)").matcher(sql.substring(close + 1));
                assertThat(ref.lookingAt()).isTrue(); String parentName = "REF_" + ref.group(1);
                constraint.putAll(row("R_CONSTRAINT_NAME", parentName, "REFERENCED_TABLE", ref.group(1),
                        "REFERENCED_STATUS", "ENABLED", "REFERENCED_TYPE", "P", "SAME_OWNER", 1, "DELETE_RULE", "NO ACTION"));
                data.addColumns(data.constraintColumns, "CONSTRAINT_NAME", parentName, ref.group(2), "POSITION");
                if (!ref.group(1).startsWith("CONTRACT_SEMANTIC_")) {
                    var parentIndex = row("TABLE_NAME", ref.group(1), "INDEX_NAME", "REFIDX_" + ref.group(1), "UNIQUENESS", "UNIQUE", "STATUS", "VALID");
                    if (!data.indexes.contains(parentIndex)) data.indexes.add(parentIndex);
                    data.addColumns(data.indexColumns, "INDEX_NAME", "REFIDX_" + ref.group(1), ref.group(2), "COLUMN_POSITION");
                }
            }
            if (type.equals("P") || type.equals("U")) {
                String index = "IDX_" + name;
                data.indexes.add(row("TABLE_NAME", table, "INDEX_NAME", index, "UNIQUENESS", "UNIQUE", "STATUS", "VALID"));
                data.addColumns(data.indexColumns, "INDEX_NAME", index, contents, "COLUMN_POSITION");
            }
            data.constraints.add(constraint);
        }
        data.indexes.add(row("TABLE_NAME", "CONTRACT_SEMANTIC_UNIT", "INDEX_NAME", "IX_SEMANTIC_UNIT_PLAN", "UNIQUENESS", "NONUNIQUE", "STATUS", "VALID"));
        data.addColumns(data.indexColumns, "INDEX_NAME", "IX_SEMANTIC_UNIT_PLAN", "PLAN_ID,UNIT_ORDINAL,EXECUTION_NO", "COLUMN_POSITION");
        return data;
    }
    static int matching(String text, int start) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) { if (text.charAt(i) == '(') depth++;
            if (text.charAt(i) == ')' && --depth == 0) return i; }
        throw new IllegalArgumentException("unbalanced DDL");
    }
    static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) row.put((String) pairs[i], pairs[i + 1]); return row;
    }
    static class Catalog {
        final List<Map<String, Object>> tables = new ArrayList<>(), columns = new ArrayList<>(), constraints = new ArrayList<>(),
                constraintColumns = new ArrayList<>(), indexes = new ArrayList<>(), indexColumns = new ArrayList<>();
        Map<String, Object> named(String name) { return constraints.stream().filter(c -> name.equals(c.get("CONSTRAINT_NAME"))).findFirst().orElseThrow(); }
        void addColumns(List<Map<String, Object>> target, String key, String name, String list, String position) {
            int ordinal = 0; for (String value : list.split(",")) {
                var item = row(key, name, "COLUMN_NAME", value.trim(), position, ++ordinal);
                if (!target.contains(item)) target.add(item);
            }
        }
        JdbcTemplate jdbc() {
            var jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForList(anyString())).thenAnswer(call -> {
                String sql = call.getArgument(0); assertThat(sql).startsWith("SELECT ");
                if (sql.contains("FROM USER_TABLES")) return tables;
                if (sql.contains("FROM USER_TAB_COLUMNS")) return columns;
                if (sql.contains("FROM USER_CONSTRAINTS")) return constraints;
                if (sql.contains("FROM USER_CONS_COLUMNS")) return constraintColumns;
                if (sql.contains("FROM USER_INDEXES")) return indexes;
                if (sql.contains("FROM USER_IND_COLUMNS")) return indexColumns;
                throw new IllegalStateException("unexpected catalog SQL");
            }); return jdbc;
        }
    }
}
