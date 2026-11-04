package com.fuquan.audit.blacklist.application;

import com.fuquan.audit.blacklist.application.port.BlacklistAlertRepository;
import com.fuquan.audit.blacklist.application.port.BlacklistCheckPort;
import com.fuquan.audit.blacklist.application.port.BlacklistRuleRepository;
import com.fuquan.audit.blacklist.application.port.BlacklistSupplierRepository;
import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import com.fuquan.audit.blacklist.domain.BlacklistMatchResult;
import com.fuquan.audit.blacklist.domain.BlacklistRule;
import com.fuquan.audit.blacklist.domain.BlacklistSupplier;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * F03 黑名单命中应用服务。
 */
public class BlacklistCheckService implements BlacklistCheckPort {

    private static final int MAX_EDIT_DISTANCE = 2;
    private static final Comparator<BlacklistMatchResult> BEST_MATCH =
            Comparator.comparingInt((BlacklistMatchResult item) -> levelRank(item.alertLevel()))
                    .thenComparingInt(BlacklistMatchResult::matchScore).reversed();

    private final BlacklistSupplierRepository supplierRepository;
    private final BlacklistAlertRepository alertRepository;
    private final BlacklistRuleRepository ruleRepository;
    private final FlowDebugLogger flowDebugLogger;

    public BlacklistCheckService(
            BlacklistSupplierRepository supplierRepository,
            BlacklistAlertRepository alertRepository,
            BlacklistRuleRepository ruleRepository,
            FlowDebugLogger flowDebugLogger) {
        this.supplierRepository = supplierRepository;
        this.alertRepository = alertRepository;
        this.ruleRepository = ruleRepository;
        this.flowDebugLogger = flowDebugLogger;
    }

    @Override
    public CheckOutcome check(CheckCommand command) {
        return check(command, loadSnapshot());
    }

    @Override
    public List<CheckOutcome> checkBatch(List<CheckCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        MatchSnapshot snapshot = loadSnapshot();
        return commands.stream().map(command -> check(command, snapshot)).toList();
    }

    private MatchSnapshot loadSnapshot() {
        List<BlacklistSupplier> suppliers = List.copyOf(supplierRepository.findAllActive());
        Map<UUID, BlacklistSupplier> suppliersById = suppliers.stream().collect(Collectors.toUnmodifiableMap(
                BlacklistSupplier::id, Function.identity(), (left, right) -> left));
        return new MatchSnapshot(suppliers, suppliersById, List.copyOf(ruleRepository.findAllActive()));
    }

    private CheckOutcome check(CheckCommand command, MatchSnapshot snapshot) {
        long startedAt = System.nanoTime();
        String supplierName = command.supplierName();
        if (supplierName == null || supplierName.isBlank()) {
            throw new IllegalArgumentException("supplierName must not be blank");
        }
        String batchId = command.batchId() != null && !command.batchId().isBlank()
                ? command.batchId() : "MANUAL";
        flowDebugLogger.log("BLACKLIST_CHECK_START", "BLACKLIST", "APPLICATION", "check",
                Map.of("supplierName", supplierName, "batchId", batchId));

        String normalizedSupplierName = supplierName.trim().toLowerCase(Locale.ROOT);
        List<BlacklistMatchResult> matches = new ArrayList<>();
        for (BlacklistSupplier entry : snapshot.suppliers()) {
            String alertLevel = alertLevel(entry, command.supplierCode(), snapshot.rules());
            if (sameCode(command.supplierCode(), entry.supplierCode())) {
                matches.add(BlacklistMatchResult.exact(
                        supplierName, command.supplierCode(), entry, alertLevel));
                continue;
            }
            if (sameName(supplierName, entry.supplierName())) {
                matches.add(BlacklistMatchResult.exact(
                        supplierName, command.supplierCode(), entry, alertLevel));
                continue;
            }
            int distance = levenshteinDistance(normalizedSupplierName,
                    entry.supplierName().trim().toLowerCase(Locale.ROOT));
            if (distance <= MAX_EDIT_DISTANCE) {
                int score = Math.max(0, 100 - distance * 20);
                matches.add(BlacklistMatchResult.fuzzy(
                        supplierName, command.supplierCode(), entry, score, alertLevel));
            }
        }

        BlacklistAlert persistedAlert = null;
        if (!matches.isEmpty()) {
            BlacklistMatchResult best = matches.stream().min(BEST_MATCH).orElseThrow();
            BlacklistSupplier matchedEntry = snapshot.suppliersById().get(best.blacklistId());
            if (matchedEntry == null) {
                throw new IllegalStateException("匹配到的黑名单记录不在当前检查快照中: " + best.blacklistId());
            }
            boolean autoResolve = snapshot.rules().stream()
                    .filter(rule -> rule.alertLevel().equals(best.alertLevel()))
                    .filter(rule -> rule.appliesTo(best.blacklistType()))
                    .anyMatch(BlacklistRule::autoResolve);
            BlacklistAlert candidate = BlacklistAlert.create(
                    batchId, command.rowNumber(), command.procurementId(), supplierName,
                    command.supplierCode(), matchedEntry, best.matchType(), best.matchScore(),
                    best.alertLevel(), autoResolve);
            BlacklistAlertRepository.AlertCreation creation = alertRepository.saveIfAbsent(candidate);
            persistedAlert = creation.created() ? creation.alert() : null;
        }

        flowDebugLogger.log("BLACKLIST_CHECK_COMPLETE", "BLACKLIST", "APPLICATION", "checkComplete",
                Map.of("supplierName", supplierName, "matched", matches.size(),
                        "durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)));
        return new CheckOutcome(
                batchId, supplierName, command.supplierCode(), !matches.isEmpty(), List.copyOf(matches),
                persistedAlert);
    }

    private record MatchSnapshot(
            List<BlacklistSupplier> suppliers,
            Map<UUID, BlacklistSupplier> suppliersById,
            List<BlacklistRule> rules) {}

    private static boolean sameCode(String left, String right) {
        return left != null && !left.isBlank() && right != null
                && left.trim().equalsIgnoreCase(right.trim());
    }

    private static boolean sameName(String left, String right) {
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private static String alertLevel(
            BlacklistSupplier entry, String supplierCode, List<BlacklistRule> rules) {
        if (containsAny(entry.blacklistType(), "行业失信", "被执行人")) return "CRITICAL";
        if ("本单位黑名单".equals(entry.blacklistType())
                && containsAny(entry.reason(), "围标", "串标", "严重违规")) return "CRITICAL";
        if ("本单位黑名单".equals(entry.blacklistType())
                && containsAny(entry.reason(), "一般违规")) return "WARNING";
        if (containsAny(entry.blacklistType(), "公开信用名单")) return "WARNING";
        // Missing supplier code is incomplete input, not a reason to downgrade a
        // matched blacklist entry. Let the configured category rule decide the level.
        return rules.stream()
                .filter(rule -> !"INCOMPLETE_DATA".equals(rule.ruleType()))
                .filter(rule -> rule.appliesTo(entry.blacklistType()))
                .map(BlacklistRule::alertLevel)
                .max(Comparator.comparingInt(BlacklistCheckService::levelRank))
                .orElse("WARNING");
    }

    private static boolean containsAny(String value, String... keywords) {
        if (value == null) return false;
        for (String keyword : keywords) {
            if (value.contains(keyword)) return true;
        }
        return false;
    }

    private static int levelRank(String level) {
        return switch (level) {
            case "CRITICAL" -> 3;
            case "WARNING" -> 2;
            default -> 1;
        };
    }

    static int levenshteinDistance(String a, String b) {
        int m = a.length();
        int n = b.length();
        if (m == 0) return n;
        if (n == 0) return m;
        if (n > m) {
            return levenshteinDistance(b, a);
        }
        int[] previous = new int[n + 1];
        int[] current = new int[n + 1];
        for (int j = 0; j <= n; j++) previous[j] = j;
        for (int i = 1; i <= m; i++) {
            current[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(
                        previous[j] + 1, current[j - 1] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[n];
    }
}
