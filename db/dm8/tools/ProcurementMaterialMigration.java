import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.io.*;

/** Explicit, once-only additive migration; credentials are environment-only. */
public final class ProcurementMaterialMigration {
    public static void main(String[] args) {
        try { run(args); }
        catch (SQLException failure) {
            System.err.println("DM8_OPERATION_FAILED sqlState=" + failure.getSQLState() + " code=" + failure.getErrorCode());
            System.exit(1);
        } catch (Exception failure) {
            System.err.println("MIGRATION_FAILED type=" + failure.getClass().getSimpleName()); System.exit(1);
        }
    }
    private static void run(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException();
        Class.forName("dm.jdbc.driver.DmDriver");
        try (var connection = DriverManager.getConnection(required("AUDIT_DB_URL"), required("AUDIT_DB_USERNAME"), required("AUDIT_DB_PASSWORD"))) {
            int tables = count(connection, "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='BUSINESS_MATERIAL_INTAKE'");
            if (count(connection, "SELECT COUNT(*) FROM ALL_TABLES WHERE TABLE_NAME='PROCUREMENT_DEMAND'") != 1) throw new IllegalStateException();
            int links = count(connection, "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='PROCUREMENT_MATERIAL_LINK'");
            int history = count(connection, "SELECT COUNT(*) FROM PROCUREMENT_DEMAND");
            System.out.println("DM8_CONNECTED intakeTables=" + tables + " linkTables=" + links + " historicalDemands=" + history);
            if (args[0].equals("preflight")) return;
            if (args[0].equals("verify")) {
                if (tables != 1 || links != 1) throw new IllegalStateException();
                System.out.println("INTAKE_SCHEMA_VERIFIED parsed=" + count(connection, "SELECT COUNT(*) FROM BUSINESS_MATERIAL_INTAKE WHERE STATUS='PARSED'"));
                return;
            }
            boolean finishLink = args[0].equals("complete-link");
            if (args.length != 3 || links != 0 || (!finishLink && (!args[0].equals("apply") || tables != 0))) throw new IllegalStateException();
            String ddl = Files.readString(Path.of(args[1]));
            if (finishLink) verifyIntakePhase(connection, ddl, tables);
            Path backup = Path.of(args[2]);
            Files.createDirectories(backup);
            snapshot(connection, "SELECT * FROM PROCUREMENT_DEMAND", backup.resolve("procurement-demand.csv"));
            snapshot(connection, "SELECT * FROM ALL_TAB_COLUMNS WHERE TABLE_NAME='PROCUREMENT_DEMAND' ORDER BY COLUMN_ID", backup.resolve("procurement-demand-columns.csv"));
            snapshot(connection, "SELECT * FROM ALL_CONSTRAINTS WHERE TABLE_NAME='PROCUREMENT_DEMAND'", backup.resolve("procurement-demand-constraints.csv"));
            snapshot(connection, "SELECT * FROM ALL_CONS_COLUMNS WHERE TABLE_NAME='PROCUREMENT_DEMAND'", backup.resolve("procurement-demand-constraint-columns.csv"));
            snapshot(connection, "SELECT * FROM ALL_INDEXES WHERE TABLE_NAME='PROCUREMENT_DEMAND'", backup.resolve("procurement-demand-indexes.csv"));
            snapshot(connection, "SELECT * FROM ALL_IND_COLUMNS WHERE TABLE_NAME='PROCUREMENT_DEMAND'", backup.resolve("procurement-demand-index-columns.csv"));
            Files.writeString(backup.resolve("applied-migration.sql"), ddl, StandardOpenOption.CREATE_NEW);
            String sql = ddl.replaceAll("(?m)^\\s*--[^\\r\\n]*", "");
            try (var statement = connection.createStatement()) {
                for (String piece : sql.split(";")) if (!piece.isBlank()) {
                    String command = piece.strip();
                    if (!finishLink || command.startsWith("CREATE TABLE PROCUREMENT_MATERIAL_LINK")
                            || command.startsWith("CREATE INDEX IDX_PROC_MATERIAL_LINK")) statement.execute(command);
                }
            }
            if (count(connection, "SELECT COUNT(*) FROM PROCUREMENT_DEMAND") != history) throw new IllegalStateException();
            System.out.println("PROCUREMENT_MATERIAL_MIGRATION_OK historicalDemands=" + history);
        }
    }
    private static void verifyIntakePhase(Connection c, String ddl, int tables) throws SQLException {
        if (tables != 1 || count(c, "SELECT COUNT(*) FROM BUSINESS_MATERIAL_INTAKE") != 0) throw new IllegalStateException();
        var expected = new java.util.HashSet<String>();
        String block = ddl.substring(ddl.indexOf("CREATE TABLE BUSINESS_MATERIAL_INTAKE"), ddl.indexOf(");"));
        var matcher = java.util.regex.Pattern.compile("(?m)^    ([A-Z0-9_]+) (?!ON )").matcher(block);
        while (matcher.find()) if (!matcher.group(1).equals("CONSTRAINT")) expected.add(matcher.group(1));
        var actual = new java.util.HashSet<String>();
        try (var s=c.createStatement(); var r=s.executeQuery("SELECT COLUMN_NAME FROM USER_TAB_COLUMNS WHERE TABLE_NAME='BUSINESS_MATERIAL_INTAKE'")) {
            while (r.next()) actual.add(r.getString(1));
        }
        if (expected.size()!=26 || !actual.equals(expected)
                || count(c, "SELECT COUNT(*) FROM USER_CONSTRAINTS WHERE TABLE_NAME='BUSINESS_MATERIAL_INTAKE' AND CONSTRAINT_TYPE='P'")!=1
                || count(c, "SELECT COUNT(*) FROM USER_CONSTRAINTS WHERE TABLE_NAME='BUSINESS_MATERIAL_INTAKE' AND CONSTRAINT_NAME IN ('UK_PROC_MAT_OPERATION','CK_PROC_MAT_STATUS')")!=2
                || count(c, "SELECT COUNT(*) FROM USER_INDEXES WHERE TABLE_NAME='BUSINESS_MATERIAL_INTAKE' AND INDEX_NAME IN ('IDX_PROC_MAT_LIST','IDX_PROC_MAT_ACTOR')")!=2)
            throw new IllegalStateException("partial intake phase differs from approved DDL");
    }
    private static int count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) { result.next(); return result.getInt(1); }
    }
    private static void snapshot(Connection connection, String sql, Path target) throws Exception {
        try (var output = Files.newBufferedWriter(target, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
             var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            int columns = result.getMetaData().getColumnCount();
            for (int i=1; i<=columns; i++) { if (i>1) output.write(','); csv(output, result.getMetaData().getColumnName(i)); }
            output.newLine();
            while (result.next()) {
                for (int i=1; i<=columns; i++) { if (i>1) output.write(','); csv(output, result.getString(i)); }
                output.newLine();
            }
        }
    }
    private static void csv(Writer writer, String value) throws IOException {
        if (value == null) { writer.write("\\N"); return; }
        writer.write('"'); writer.write(value.replace("\"", "\"\"")); writer.write('"');
    }
    private static String required(String key) {
        String value = System.getenv(key); if (value == null || value.isBlank()) throw new IllegalStateException(); return value;
    }
}
