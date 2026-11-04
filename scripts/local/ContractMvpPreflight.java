import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/** Read-only DM8/schema/worker probe used by contract-mvp.ps1. */
public final class ContractMvpPreflight {
    private static final List<String> EXISTING_V2_TABLES = List.of(
            "PROJECT_FACT_EXTRACTION_ATTEMPT",
            "PROJECT_FACT_EXTRACTION_CANDIDATE",
            "CONTRACT_REVIEW_CLAUSE_COMPARISON",
            "CONTRACT_REVIEW_CLAUSE_VALUE",
            "CONTRACT_REVIEW_CLAUSE_VALUE_EVIDENCE");
    private static final List<String> NEW_TABLES = List.of(
            "PROJECT_FACT_EXTRACTION_REJECTION", "CONTRACT_MODEL_CALL_AUDIT");

    public static void main(String[] args) throws Exception {
        try (Connection connection = connect()) {
            connection.setReadOnly(true);
            requireDm8(connection);
            requireExistingTables(connection);
            String migrationState = migrationState(connection);
            String ownerUniqueState = ownerUniqueState(connection, migrationState);
            int claimable = claimable(connection);
            int expired = expired(connection);
            int running = scalar(connection,
                    "SELECT COUNT(*) FROM CONTRACT_REVIEW_STEP_ATTEMPT WHERE STATUS='RUNNING'");
            int validLease = scalar(connection,
                    "SELECT COUNT(*) FROM CONTRACT_REVIEW_STEP_ATTEMPT "
                    + "WHERE STATUS='RUNNING' AND LEASE_OWNER IS NOT NULL "
                    + "AND LEASE_UNTIL > CURRENT_TIMESTAMP");
            int factInProgress = scalar(connection,
                    "SELECT COUNT(*) FROM PROJECT_FACT_EXTRACTION_ATTEMPT WHERE STATUS='IN_PROGRESS'");
            int factRecent = recentInProgressFactAttempts(connection, Duration.ofMinutes(15));
            int roleBinding = scalar(connection,
                    "SELECT COUNT(*) FROM USER_CONSTRAINTS "
                    + "WHERE CONSTRAINT_NAME='UK_PROJECT_FILE_ROLE_DECISION_PARSE_BINDING' "
                    + "AND STATUS='ENABLED'");
            if (roleBinding != 1) fail("candidate authority constraint is absent or disabled");
            System.out.println("DM8_IDENTITY=DM8_MAJOR_8");
            System.out.println("CONTRACT_FACT_V2_MIGRATION_STATE=" + migrationState);
            System.out.println("CONTRACT_MODEL_CALL_OWNER_UNIQUE_STATE=" + ownerUniqueState);
            System.out.println("WORKER_GATE claimable=" + claimable + " expired=" + expired
                    + " running=" + running + " validLease=" + validLease
                    + " factInProgress=" + factInProgress + " factRecent=" + factRecent);
            if (factInProgress > 0) printInProgressFactAttempts(connection);
            System.out.println("AUTHORITY_CONSTRAINT=ENABLED");
            connection.rollback();
            if (claimable != 0 || expired != 0 || running != 0 || validLease != 0
                    || factRecent != 0) {
                fail("worker or fact-extraction takeover risk is non-zero");
            }
        }
    }

    private static Connection connect() throws Exception {
        String url = required("AUDIT_DB_URL");
        String username = required("AUDIT_DB_USERNAME");
        String normalized = username.toUpperCase(Locale.ROOT);
        if (normalized.equals("SYSDBA") || normalized.equals("SYSAUDITOR")
                || normalized.equals("SYS") || normalized.equals("SYSTEM")) {
            fail("system database accounts are prohibited");
        }
        Connection connection = DriverManager.getConnection(
                url, username, required("AUDIT_DB_PASSWORD"));
        connection.setAutoCommit(false);
        return connection;
    }

    private static void requireDm8(Connection connection) throws Exception {
        String product = connection.getMetaData().getDatabaseProductName().toUpperCase(Locale.ROOT);
        if (!product.contains("DM") || connection.getMetaData().getDatabaseMajorVersion() != 8) {
            fail("representative DM8 major version 8 required");
        }
    }

    private static void requireExistingTables(Connection connection) throws Exception {
        for (String table : EXISTING_V2_TABLES) {
            if (tableCount(connection, table) != 1) fail("missing existing table " + table);
        }
    }

    private static String migrationState(Connection connection) throws Exception {
        int newTables = 0;
        for (String table : NEW_TABLES) newTables += tableCount(connection, table);
        int valuePrecision = precision(connection, "PROJECT_EXTRACTION_RESULT", "VALUE_AMOUNT");
        int valueScale = scale(connection, "PROJECT_EXTRACTION_RESULT", "VALUE_AMOUNT");
        int unitPrecision = precision(connection, "CONTRACT_CANDIDATE_LINE_ITEM", "UNIT_PRICE");
        int unitScale = scale(connection, "CONTRACT_CANDIDATE_LINE_ITEM", "UNIT_PRICE");
        boolean pre = newTables == 0 && valuePrecision == 18 && valueScale == 2
                && unitPrecision == 18 && unitScale == 2;
        boolean applied = newTables == NEW_TABLES.size() && valuePrecision == 20 && valueScale == 4
                && unitPrecision == 20 && unitScale == 4;
        if (!pre && !applied) {
            fail("new migration is partial or catalog precision changed");
        }
        if (applied) {
            requireConstraint(connection, "UK_PROJECT_FACT_EXTRACTION_REJECTION");
            requireConstraint(connection, "CK_CONTRACT_MODEL_CALL_OWNER");
        }
        return applied ? "APPLIED" : "PRE";
    }

    private static String ownerUniqueState(Connection connection, String migrationState)
            throws Exception {
        if (!"APPLIED".equals(migrationState)) return "NOT_APPLICABLE";
        int fact = enabledConstraintCount(connection, "UK_CONTRACT_MODEL_CALL_FACT");
        int review = enabledConstraintCount(connection, "UK_CONTRACT_MODEL_CALL_REVIEW");
        int ownerAware = enabledConstraintCount(connection, "UK_CONTRACT_MODEL_CALL_OWNER_CALL");
        if (fact == 1 && review == 1 && ownerAware == 0) return "PRE";
        if (fact == 0 && review == 0 && ownerAware == 1) return "APPLIED";
        fail("model-call owner uniqueness is partial or drifted");
        return "UNKNOWN";
    }

    private static int claimable(Connection connection) throws Exception {
        return scalar(connection, """
                SELECT COUNT(*) FROM CONTRACT_REVIEW_STEP_ATTEMPT a
                JOIN CONTRACT_REVIEW_STEP s ON s.RUN_ID=a.RUN_ID AND s.STEP_ID=a.STEP_ID
                JOIN CONTRACT_REVIEW_RUN r ON r.RUN_ID=a.RUN_ID
                WHERE a.STATUS='PENDING' AND s.STATUS='PENDING'
                  AND s.CURRENT_ATTEMPT_NO=a.ATTEMPT_NO
                  AND r.STATUS IN ('PENDING','RUNNING')
                  AND a.LEASE_OWNER IS NULL AND a.LEASE_UNTIL IS NULL
                  AND a.EXTERNAL_CALL_STATE='NOT_STARTED'
                """);
    }

    private static int expired(Connection connection) throws Exception {
        return scalar(connection, """
                SELECT COUNT(*) FROM CONTRACT_REVIEW_STEP_ATTEMPT a
                JOIN CONTRACT_REVIEW_STEP s ON s.RUN_ID=a.RUN_ID AND s.STEP_ID=a.STEP_ID
                JOIN CONTRACT_REVIEW_RUN r ON r.RUN_ID=a.RUN_ID
                WHERE a.STATUS='RUNNING' AND s.STATUS='RUNNING'
                  AND s.CURRENT_ATTEMPT_NO=a.ATTEMPT_NO
                  AND r.STATUS IN ('RUNNING','UNKNOWN')
                  AND a.LEASE_OWNER IS NOT NULL AND a.LEASE_UNTIL <= CURRENT_TIMESTAMP
                """);
    }

    private static int tableCount(Connection connection, String name) throws Exception {
        return scalar(connection, "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='" + name + "'");
    }

    private static void printInProgressFactAttempts(Connection connection) throws Exception {
        String sql = "SELECT ATTEMPT_ID, PROJECT_ID, FILE_ID, PARSE_RESULT_ID, PROVIDER, MODEL_NAME, "
                + "CREATED_AT, UPDATED_AT, CALL_COUNT, VERSION_NO "
                + "FROM PROJECT_FACT_EXTRACTION_ATTEMPT WHERE STATUS='IN_PROGRESS' "
                + "ORDER BY UPDATED_AT, ATTEMPT_ID";
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (ResultSet result = statement.executeQuery(sql)) {
                while (result.next()) {
                    System.out.println("FACT_IN_PROGRESS attemptId=" + result.getString(1)
                            + " projectId=" + result.getString(2)
                            + " fileId=" + result.getString(3)
                            + " parseResultId=" + result.getString(4)
                            + " provider=" + result.getString(5)
                            + " model=" + result.getString(6)
                            + " createdAt=" + result.getString(7)
                            + " updatedAt=" + result.getString(8)
                            + " callCount=" + result.getInt(9)
                            + " versionNo=" + result.getInt(10));
                }
            }
        }
    }

    private static int recentInProgressFactAttempts(Connection connection, Duration maximumAge)
            throws Exception {
        Instant cutoff = Instant.now().minus(maximumAge);
        int count = 0;
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (ResultSet result = statement.executeQuery(
                    "SELECT UPDATED_AT FROM PROJECT_FACT_EXTRACTION_ATTEMPT WHERE STATUS='IN_PROGRESS'")) {
                while (result.next()) {
                    Instant updatedAt = result.getTimestamp(1).toLocalDateTime().toInstant(ZoneOffset.UTC);
                    if (updatedAt.isAfter(cutoff)) count++;
                }
            }
        }
        return count;
    }

    private static int precision(Connection connection, String table, String column) throws Exception {
        return scalar(connection, "SELECT DATA_PRECISION FROM USER_TAB_COLUMNS WHERE TABLE_NAME='"
                + table + "' AND COLUMN_NAME='" + column + "'");
    }

    private static int scale(Connection connection, String table, String column) throws Exception {
        return scalar(connection, "SELECT DATA_SCALE FROM USER_TAB_COLUMNS WHERE TABLE_NAME='"
                + table + "' AND COLUMN_NAME='" + column + "'");
    }

    private static void requireConstraint(Connection connection, String name) throws Exception {
        if (enabledConstraintCount(connection, name) != 1) {
            fail("missing or disabled migration constraint " + name);
        }
    }

    private static int enabledConstraintCount(Connection connection, String name) throws Exception {
        return scalar(connection, "SELECT COUNT(*) FROM USER_CONSTRAINTS WHERE CONSTRAINT_NAME='"
                + name + "' AND STATUS='ENABLED'");
    }

    private static int scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (ResultSet result = statement.executeQuery(sql)) {
                if (!result.next()) fail("catalog query returned no row");
                return result.getInt(1);
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) fail("missing environment " + name);
        return value;
    }

    private static void fail(String message) { throw new IllegalStateException(message); }
}
