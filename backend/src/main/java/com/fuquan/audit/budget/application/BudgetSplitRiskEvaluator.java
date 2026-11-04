package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.domain.BudgetSplitRiskRecord;
import com.fuquan.audit.budget.domain.BudgetSplitRiskRule;
import com.fuquan.audit.budget.domain.ProcurementDemand;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** F05 确定性金额拆分检测；不使用 AI 作金额决策。 */
@Component
public class BudgetSplitRiskEvaluator {

    public Evaluation evaluate(List<ProcurementDemand> input, List<BudgetSplitRiskRule> rules) {
        if (input == null || input.size() < 2) {
            throw new IllegalArgumentException("至少需要选择两条采购申报记录");
        }
        for (ProcurementDemand demand : input) {
            validateRequiredData(demand);
        }
        List<ProcurementDemand> demands = input.stream()
                .sorted(Comparator.comparing(ProcurementDemand::createdTime)
                        .thenComparing(ProcurementDemand::id))
                .toList();
        if (rules.isEmpty()) {
            throw new IllegalStateException("未配置有效的项目拆分风险规则");
        }

        BigDecimal totalAmount = demands.stream().map(ProcurementDemand::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, List<ProcurementDemand>> groups = new LinkedHashMap<>();
        for (ProcurementDemand demand : demands) {
            groups.computeIfAbsent(groupKey(demand), ignored -> new ArrayList<>()).add(demand);
        }

        List<BudgetSplitRiskRecord> findings = new ArrayList<>();
        for (List<ProcurementDemand> group : groups.values()) {
            BudgetSplitRiskRecord best = null;
            for (BudgetSplitRiskRule rule : rules) {
                BudgetSplitRiskRecord candidate = bestWindow(group, rule);
                if (candidate != null && (best == null || candidate.riskScore() > best.riskScore())) {
                    best = candidate;
                }
            }
            if (best != null) findings.add(best);
        }
        findings.sort(Comparator.comparingInt(BudgetSplitRiskRecord::riskScore).reversed()
                .thenComparing(BudgetSplitRiskRecord::budgetSubject));

        Set<UUID> riskyIds = new HashSet<>();
        findings.forEach(record -> riskyIds.addAll(record.procurementIds()));
        int riskScore = findings.stream().mapToInt(BudgetSplitRiskRecord::riskScore).max().orElse(0);
        String riskLevel = findings.stream().anyMatch(record -> "CRITICAL".equals(record.riskLevel()))
                ? "CRITICAL" : findings.isEmpty() ? "INFO" : "WARNING";
        BigDecimal splitRatio = percent(riskyIds.size(), demands.size());
        int mostConcentratedWindowCount = findings.stream()
                .mapToInt(BudgetSplitRiskRecord::procurementCount).max().orElse(0);
        BigDecimal timeConcentration = percent(mostConcentratedWindowCount, demands.size());
        BigDecimal supplierConcentration = supplierConcentration(demands);
        return new Evaluation(totalAmount, riskLevel, riskScore, splitRatio,
                timeConcentration, supplierConcentration, findings);
    }

    private BudgetSplitRiskRecord bestWindow(
            List<ProcurementDemand> source,
            BudgetSplitRiskRule rule) {
        List<ProcurementDemand> eligible = source.stream()
                .filter(item -> item.amount().compareTo(rule.individualAmountLimit()) < 0)
                .sorted(Comparator.comparing(ProcurementDemand::createdTime))
                .toList();
        BudgetSplitRiskRecord best = null;
        int start = 0;
        for (int end = 0; end < eligible.size(); end++) {
            Instant windowEnd = eligible.get(end).createdTime();
            Instant earliest = windowEnd.minus(rule.windowDays(), ChronoUnit.DAYS);
            while (start <= end && eligible.get(start).createdTime().isBefore(earliest)) start++;
            if (end - start + 1 < rule.minimumRecordCount()) continue;
            List<ProcurementDemand> window = eligible.subList(start, end + 1);
            BigDecimal amount = window.stream().map(ProcurementDemand::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (amount.compareTo(rule.aggregateAmountThreshold()) < 0) continue;
            int score = score(window, amount, rule);
            String level = score >= rule.criticalScore() ? "CRITICAL" : "WARNING";
            BudgetSplitRiskRecord candidate = new BudgetSplitRiskRecord(
                    rule.ruleCode(), subjectLabel(window.get(0)), commonSupplier(window),
                    amount, window.size(), score, level, window.get(0).createdTime(),
                    window.get(window.size() - 1).createdTime(),
                    window.stream().map(ProcurementDemand::id).toList());
            if (best == null || candidate.riskScore() > best.riskScore()
                    || (candidate.riskScore() == best.riskScore()
                    && candidate.totalAmount().compareTo(best.totalAmount()) > 0)) {
                best = candidate;
            }
        }
        return best;
    }

    private int score(
            List<ProcurementDemand> window,
            BigDecimal amount,
            BudgetSplitRiskRule rule) {
        BigDecimal amountFactor = amount.divide(rule.aggregateAmountThreshold(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).min(BigDecimal.valueOf(100));
        long spanDays = Duration.between(
                window.get(0).createdTime(), window.get(window.size() - 1).createdTime()).toDays();
        BigDecimal timeFactor = BigDecimal.valueOf(100)
                .subtract(BigDecimal.valueOf(Math.min(100,
                        spanDays * 100D / rule.windowDays())));
        BigDecimal countFactor = BigDecimal.valueOf(Math.min(100,
                window.size() * 100D / (rule.minimumRecordCount() + 1D)));
        boolean namedSupplier = commonSupplier(window) != null;
        BigDecimal supplierFactor = BigDecimal.valueOf(namedSupplier ? 100 : 50);
        int computed = amountFactor.multiply(new BigDecimal("0.35"))
                .add(timeFactor.multiply(new BigDecimal("0.25")))
                .add(countFactor.multiply(new BigDecimal("0.20")))
                .add(supplierFactor.multiply(new BigDecimal("0.20")))
                .setScale(0, RoundingMode.HALF_UP).intValue();
        return Math.max(rule.warningScore(), Math.min(100, computed));
    }

    private static String groupKey(ProcurementDemand demand) {
        String subject = normalize(blankToNull(demand.subjectCode()) != null
                ? demand.subjectCode() : demand.subjectName());
        String projectName = normalize(demand.procurementName());
        return subject + "|" + projectName;
    }

    private static String subjectLabel(ProcurementDemand demand) {
        String name = blankToNull(demand.subjectName());
        return name != null ? name : demand.subjectCode();
    }

    private static BigDecimal supplierConcentration(List<ProcurementDemand> demands) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ProcurementDemand demand : demands) {
            String supplier = normalize(demand.supplierName());
            if (!supplier.isEmpty()) counts.merge(supplier, 1, Integer::sum);
        }
        int maximum = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return percent(maximum, demands.size());
    }

    private static String commonSupplier(List<ProcurementDemand> demands) {
        String supplier = blankToNull(demands.get(0).supplierName());
        if (supplier == null) return null;
        String normalized = normalize(supplier);
        boolean sameSupplier = demands.stream()
                .map(ProcurementDemand::supplierName)
                .map(BudgetSplitRiskEvaluator::normalize)
                .allMatch(normalized::equals);
        return sameSupplier ? supplier : null;
    }

    private static void validateRequiredData(ProcurementDemand demand) {
        if (demand.amount() == null || demand.amount().signum() <= 0) {
            throw new IllegalArgumentException("采购申报记录缺少有效金额: " + demand.id());
        }
        if (demand.createdTime() == null) {
            throw new IllegalArgumentException("采购申报记录缺少记录时间: " + demand.id());
        }
        if (blankToNull(demand.subjectCode()) == null && blankToNull(demand.subjectName()) == null) {
            throw new IllegalArgumentException("采购申报记录缺少预算科目: " + demand.id());
        }
        if (blankToNull(demand.procurementName()) == null) {
            throw new IllegalArgumentException("采购申报记录缺少项目名称: " + demand.id());
        }
    }

    private static BigDecimal percent(int numerator, int denominator) {
        if (denominator == 0) return BigDecimal.ZERO.setScale(2);
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private static String normalize(String value) {
        String text = blankToNull(value);
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record Evaluation(
            BigDecimal totalAmount,
            String riskLevel,
            int riskScore,
            BigDecimal splitRatio,
            BigDecimal timeConcentration,
            BigDecimal supplierConcentration,
            List<BudgetSplitRiskRecord> records) {
        public Evaluation {
            records = List.copyOf(records);
        }
    }
}
