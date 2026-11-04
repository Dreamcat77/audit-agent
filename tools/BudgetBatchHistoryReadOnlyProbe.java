import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.application.port.BudgetHistoryFilter;
import com.fuquan.audit.integration.dm8.Dm8BudgetMaterialBatchRepository;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Executes the actual history adapter on DM8 using only SELECT statements; prints counts, never material names. */
public final class BudgetBatchHistoryReadOnlyProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("synthetic acceptance username required");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (var connection = DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),
                System.getenv("AUDIT_DB_USERNAME"), System.getenv("AUDIT_DB_PASSWORD"))) {
            connection.setReadOnly(true);
            String actor;
            try (var statement = connection.prepareStatement("SELECT ID FROM SYS_USER WHERE USERNAME=?")) {
                statement.setString(1, args[0]);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("ACCEPTANCE_ACTOR_MISSING");
                    actor = rows.getString(1);
                    if (rows.next()) throw new IllegalStateException("ACCEPTANCE_ACTOR_AMBIGUOUS");
                }
            }
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            var repository = new Dm8BudgetMaterialBatchRepository(jdbc, new ObjectMapper());
            var filter = BudgetHistoryFilter.of(null, null, null);
            long total = repository.countHistory(actor, filter);
            var first = repository.findHistoryPage(actor, filter, 0, 2);
            var second = repository.findHistoryPage(actor, filter, 2, 2);
            var identities = new HashSet<UUID>();
            for (var row : java.util.stream.Stream.concat(first.stream(), second.stream()).toList()) {
                if (!identities.add(row.resourceId())) throw new AssertionError("PAGINATION_DUPLICATE");
                String owner = row.batchId() != null
                        ? jdbc.queryForObject("SELECT ACTOR_ID FROM BUDGET_MATERIAL_BATCH WHERE ID=?", String.class, row.batchId().toString())
                        : jdbc.queryForObject("SELECT ACTOR_ID FROM BUDGET_MATERIAL_JOB WHERE ID=?", String.class, row.taskId().toString());
                if (!actor.equals(owner)) throw new AssertionError("CROSS_ACTOR_HISTORY");
            }
            if (first.size() != Math.min(total, 2L)) throw new AssertionError("PAGE_COUNT_MISMATCH");
            var named = BudgetHistoryFilter.of("SYNTHETIC", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
            repository.findHistoryPage(actor, named, 0, 2);
            long matched = repository.countHistory(actor, named);
            var absent = BudgetHistoryFilter.of("BATCH_HISTORY_PROBE_MUST_NOT_EXIST_" + UUID.randomUUID(), null, null);
            if (repository.countHistory(actor, absent) != 0 || !repository.findHistoryPage(actor, absent, 0, 2).isEmpty())
                throw new AssertionError("NAME_FILTER_FAILED");
            if (repository.countHistory(UUID.randomUUID().toString(), filter) != 0)
                throw new AssertionError("UNKNOWN_ACTOR_EXPOSED_HISTORY");
            System.out.printf("BUDGET_BATCH_HISTORY_DM8_READ_ONLY=PASS total=%d firstPage=%d secondPage=%d syntheticMatches=%d writes=0%n",
                    total, first.size(), second.size(), matched);
        } catch (SQLException failure) {
            System.err.println("BUDGET_BATCH_HISTORY_DM8_READ_ONLY=FAILED sqlState=" + failure.getSQLState() + " dbCode=" + failure.getErrorCode());
            System.exit(1);
        }
    }
}
