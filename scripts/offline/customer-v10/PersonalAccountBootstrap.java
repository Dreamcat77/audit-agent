import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.CharBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/** Creates only missing module accounts and idempotently adds their module role. */
public final class PersonalAccountBootstrap {
    private record Account(String username, String realName, String roleCode) {}
    private static final List<Account> ACCOUNTS = List.of(
            new Account("caigou", "采购", "PROCUREMENT_USER"),
            new Account("yusuan", "预算", "BUDGET_USER"),
            new Account("hetong", "合同", "CONTRACT_USER"));

    private PersonalAccountBootstrap() {}

    public static void main(String[] args) {
        if (args.length != 2 || !(args[0].equals("check") || args[0].equals("apply"))) {
            fail("USAGE");
        }
        try (Connection connection = DriverManager.getConnection(
                required("AUDIT_DB_URL"), required("AUDIT_DB_USERNAME"), required("AUDIT_DB_PASSWORD"))) {
            if (!connection.getMetaData().getDatabaseProductName().toUpperCase(Locale.ROOT).contains("DM")) fail("NOT_DM8");
            if (connection.getMetaData().getUserName().toUpperCase(Locale.ROOT).matches("SYSDBA|SYSAUDITOR")) fail("ADMIN_ACCOUNT_FORBIDDEN");
            boolean checkOnly = args[0].equals("check");
            if (!checkOnly) connection.setAutoCommit(false);
            ensureRoleSeeds(connection, Path.of(args[1]), checkOnly);
            int missing = 0;
            for (Account account : ACCOUNTS) {
                if (!userExists(connection, account.username())) missing++;
                if (!roleExists(connection, account.roleCode()) && args[0].equals("apply")) fail("ROLE_UNAVAILABLE");
            }
            if (args[0].equals("check")) {
                if (missing > 0 && System.console() == null && !Files.isReadable(Path.of("/dev/tty"))) {
                    fail("INTERACTIVE_TERMINAL_REQUIRED");
                }
                System.out.println("PERSONAL_ACCOUNTS_PREFLIGHT=PASS missing=" + missing + " roles_seeded_or_ready=true passwords_preserved=true");
                return;
            }
            char[] password = missing == 0 ? null : readPassword();
            try {
                String hash = password == null ? null : PasswordEncoderFactories.createDelegatingPasswordEncoder()
                        .encode(CharBuffer.wrap(password));
                int created = 0;
                int rolesAdded = 0;
                for (Account account : ACCOUNTS) {
                    String userId = findUserId(connection, account.username());
                    if (userId == null) {
                        userId = UUID.randomUUID().toString();
                        insertUser(connection, userId, account, hash);
                        created++;
                    }
                    rolesAdded += addRole(connection, userId, account.roleCode());
                }
                connection.commit();
                System.out.println("PERSONAL_ACCOUNTS=PASS created=" + created + " roles_added=" + rolesAdded
                        + " existing_passwords_preserved=true");
            } catch (Exception error) {
                connection.rollback();
                throw error;
            } finally {
                if (password != null) Arrays.fill(password, '\0');
            }
        } catch (Exception error) {
            String reason = error instanceof IllegalStateException && error.getMessage().matches("[A-Z0-9_]+")
                    ? error.getMessage() : "DATABASE_OR_BOOTSTRAP_ERROR";
            System.err.println("PERSONAL_ACCOUNTS_BLOCKED reasonCode=" + reason);
            System.exit(1);
        }
    }

    private static void ensureRoleSeeds(Connection connection, Path sqlPath, boolean checkOnly) throws Exception {
        if (!Files.isRegularFile(sqlPath)) fail("ROLE_SEED_MISSING");
        String source = Files.readString(sqlPath, StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--[^\\n]*", "");
        for (String statement : source.split(";")) {
            if (statement.isBlank()) continue;
            String sql = statement.trim();
            if (checkOnly) {
                String code = sql.contains("PROCUREMENT_USER") ? "PROCUREMENT_USER"
                        : sql.contains("BUDGET_USER") ? "BUDGET_USER" : "CONTRACT_USER";
                if (!roleExists(connection, code)) continue;
            } else {
                try (Statement command = connection.createStatement()) { command.executeUpdate(sql); }
            }
        }
    }

    private static boolean userExists(Connection connection, String username) throws SQLException {
        return findUserId(connection, username) != null;
    }

    private static String findUserId(Connection connection, String username) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT ID FROM SYS_USER WHERE USERNAME=?")) {
            query.setString(1, username);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                String id = rows.getString(1);
                if (rows.next()) fail("USERNAME_NOT_UNIQUE");
                return id;
            }
        }
    }

    private static boolean roleExists(Connection connection, String roleCode) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(1) FROM SYS_ROLE WHERE ROLE_CODE=? AND STATUS='ACTIVE'")) {
            query.setString(1, roleCode);
            try (ResultSet rows = query.executeQuery()) { rows.next(); return rows.getInt(1) == 1; }
        }
    }

    private static void insertUser(Connection connection, String id, Account account, String hash) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO SYS_USER (ID, USERNAME, PASSWORD_HASH, REAL_NAME, STATUS,
                    FAILED_LOGIN_COUNT, LOCKED_UNTIL, LAST_LOGIN_AT, PASSWORD_CHANGED_AT,
                    CREATED_AT, UPDATED_AT, VERSION_NO)
                VALUES (?, ?, ?, ?, 'ACTIVE', 0, NULL, NULL, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """)) {
            insert.setString(1, id); insert.setString(2, account.username());
            insert.setString(3, hash); insert.setString(4, account.realName()); insert.executeUpdate();
        }
    }

    private static int addRole(Connection connection, String userId, String roleCode) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO SYS_USER_ROLE (USER_ID, ROLE_ID)
                SELECT ?, ID FROM SYS_ROLE WHERE ROLE_CODE=? AND STATUS='ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM SYS_USER_ROLE WHERE USER_ID=? AND ROLE_ID=SYS_ROLE.ID)
                """)) {
            insert.setString(1, userId); insert.setString(2, roleCode); insert.setString(3, userId);
            return insert.executeUpdate();
        }
    }

    private static char[] readPassword() throws Exception {
        Console console = System.console();
        if (console != null) return confirmed(console);
        File tty = new File("/dev/tty");
        if (!tty.exists()) fail("INTERACTIVE_TERMINAL_REQUIRED");
        try (FileInputStream input = new FileInputStream(tty); FileOutputStream output = new FileOutputStream(tty)) {
            setEcho(false);
            try {
                output.write("新建账号共用初始密码\n密码: ".getBytes(StandardCharsets.UTF_8)); output.flush();
                char[] first = readLine(input);
                output.write("确认密码: ".getBytes(StandardCharsets.UTF_8)); output.flush();
                char[] second = readLine(input);
                if (first.length < 8 || !Arrays.equals(first, second)) {
                    Arrays.fill(first, '\0'); Arrays.fill(second, '\0'); fail("PASSWORD_CONFIRMATION_FAILED");
                }
                Arrays.fill(second, '\0'); return first;
            } finally { setEcho(true); output.write('\n'); output.flush(); }
        }
    }

    private static char[] confirmed(Console console) {
        char[] first = console.readPassword("新建账号共用初始密码: ");
        char[] second = console.readPassword("确认密码: ");
        if (first == null || second == null || first.length < 8 || !Arrays.equals(first, second)) {
            if (first != null) Arrays.fill(first, '\0');
            if (second != null) Arrays.fill(second, '\0');
            fail("PASSWORD_CONFIRMATION_FAILED");
        }
        Arrays.fill(second, '\0'); return first;
    }

    private static char[] readLine(FileInputStream input) throws Exception {
        char[] value = new char[1024];
        int length = 0;
        int next;
        while ((next = input.read()) != -1 && next != '\n' && next != '\r') {
            if (length == value.length) { Arrays.fill(value, '\0'); fail("PASSWORD_TOO_LONG"); }
            value[length++] = (char) next;
        }
        if (next == -1) { Arrays.fill(value, '\0'); fail("PASSWORD_INPUT_MISSING"); }
        char[] result = Arrays.copyOf(value, length);
        Arrays.fill(value, '\0');
        return result;
    }

    private static void setEcho(boolean enabled) throws Exception {
        Process process = new ProcessBuilder("stty", enabled ? "echo" : "-echo").redirectInput(new File("/dev/tty")).start();
        if (process.waitFor() != 0) fail("TERMINAL_ECHO_CONTROL_FAILED");
    }

    private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) fail("DB_ENV_INCOMPLETE"); return value; }
    private static void fail(String reason) { throw new IllegalStateException(reason); }
}
