package com.fuquan.audit.procurement.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** F06 询比价批次分析结果。 */
public record ProcurementQuoteCheck(
        UUID id,
        String batchId,
        BigDecimal budgetAmount,
        BigDecimal averageQuote,
        BigDecimal standardDeviation,
        BigDecimal coefficientOfVariation,
        boolean highlyConcentrated,
        String riskLevel,
        Instant createdTime,
        List<Quote> quotes,
        ProcurementQuoteComparison comparison) {

    public ProcurementQuoteCheck(UUID id, String batchId, BigDecimal budgetAmount, BigDecimal averageQuote,
            BigDecimal standardDeviation, BigDecimal coefficientOfVariation, boolean highlyConcentrated,
            String riskLevel, Instant createdTime, List<Quote> quotes) {
        this(id, batchId, budgetAmount, averageQuote, standardDeviation, coefficientOfVariation,
                highlyConcentrated, riskLevel, createdTime, quotes, null);
    }

    public ProcurementQuoteCheck withIdentityAndComparison(UUID stableId, ProcurementQuoteComparison detail) {
        return new ProcurementQuoteCheck(stableId, batchId, budgetAmount, averageQuote, standardDeviation,
                coefficientOfVariation, highlyConcentrated, riskLevel, createdTime, quotes, detail);
    }

    private static final BigDecimal HIGH_CONCENTRATION_THRESHOLD = new BigDecimal("0.05");
    private static final BigDecimal SIGNIFICANT_DEVIATION_THRESHOLD = new BigDecimal("0.05");

    public ProcurementQuoteCheck {
        Objects.requireNonNull(id, "id must not be null");
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("batchId must not be blank");
        }
        requireAmount(budgetAmount, "budgetAmount");
        requireAmount(averageQuote, "averageQuote");
        requireScale(standardDeviation, 4, "standardDeviation");
        requireScale(coefficientOfVariation, 4, "coefficientOfVariation");
        if (riskLevel == null || !switch (riskLevel) {
            case "CRITICAL", "WARNING", "INFO" -> true;
            default -> false;
        }) {
            throw new IllegalArgumentException("riskLevel must not be blank");
        }
        Objects.requireNonNull(createdTime, "createdTime must not be null");
        quotes = List.copyOf(Objects.requireNonNull(quotes, "quotes must not be null"));
    }

    public static ProcurementQuoteCheck analyze(String batchId, BigDecimal budgetAmount,
                                                List<QuoteInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("至少需要一条有效报价数据");
        }
        BigDecimal budget = money(budgetAmount, "预算金额");
        List<QuoteInput> normalized = inputs.stream().map(QuoteInput::normalized).toList();
        BigDecimal count = BigDecimal.valueOf(normalized.size());
        BigDecimal sum = normalized.stream()
                .map(QuoteInput::quoteAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = sum.divide(count, 12, RoundingMode.HALF_UP);
        BigDecimal variance = normalized.stream()
                .map(item -> item.quoteAmount().subtract(average).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(count, 12, RoundingMode.HALF_UP);
        BigDecimal standardDeviation = variance.sqrt(new MathContext(16, RoundingMode.HALF_UP));
        BigDecimal cv = average.signum() == 0
                ? BigDecimal.ZERO
                : standardDeviation.divide(average, 12, RoundingMode.HALF_UP);
        BigDecimal cvRounded = cv.setScale(4, RoundingMode.HALF_UP);
        // Use the unrounded value for the rule; the four-decimal value is presentation/storage only.
        boolean concentrated = normalized.size() >= 3
                && cv.compareTo(HIGH_CONCENTRATION_THRESHOLD) < 0;
        boolean anyOverBudget = normalized.stream()
                .anyMatch(item -> item.quoteAmount().compareTo(budget) > 0);
        String risk = anyOverBudget && concentrated ? "CRITICAL"
                : anyOverBudget || concentrated ? "WARNING" : "INFO";

        List<Quote> quotes = normalized.stream()
                .map(item -> toQuote(item, budget, average))
                .toList();
        return new ProcurementQuoteCheck(
                UUID.randomUUID(), batchId, budget, average.setScale(2, RoundingMode.HALF_UP),
                standardDeviation.setScale(4, RoundingMode.HALF_UP), cvRounded, concentrated,
                risk, Instant.now(), quotes);
    }

    private static Quote toQuote(QuoteInput input, BigDecimal budget, BigDecimal average) {
        BigDecimal amount = input.quoteAmount();
        boolean overBudget = amount.compareTo(budget) > 0;
        BigDecimal overAmount = overBudget ? amount.subtract(budget).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2);
        BigDecimal overRatio = overBudget && budget.signum() > 0
                ? overAmount.divide(budget, 8, RoundingMode.HALF_UP)
                        .setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(4);
        BigDecimal deviationRatio = average.signum() == 0
                ? BigDecimal.ZERO.setScale(4)
                : amount.subtract(average).abs().divide(average, 8, RoundingMode.HALF_UP)
                        .setScale(4, RoundingMode.HALF_UP);
        boolean significantlyDeviated = average.signum() != 0 && amount.subtract(average).abs()
                .compareTo(average.multiply(SIGNIFICANT_DEVIATION_THRESHOLD)) >= 0;
        boolean suspicious = overBudget || significantlyDeviated;
        String reason = overBudget && significantlyDeviated
                ? "超预算且报价偏离均值"
                : overBudget ? "报价超过预算"
                : suspicious ? "报价显著偏离均值" : null;
        return new Quote(UUID.randomUUID(), input.rowNumber(), input.supplierName(), amount,
                input.productDescription(), overBudget, overAmount, overRatio, deviationRatio,
                suspicious, reason);
    }

    private static BigDecimal money(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(name + "必须是非负金额");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static void requireAmount(BigDecimal value, String name) {
        if (value == null || value.scale() != 2) {
            throw new IllegalArgumentException(name + " must use scale 2");
        }
    }

    private static void requireScale(BigDecimal value, int scale, String name) {
        if (value == null || value.scale() != scale) {
            throw new IllegalArgumentException(name + " must use scale " + scale);
        }
    }

    public record QuoteInput(int rowNumber, String supplierName, BigDecimal quoteAmount,
                             String productDescription) {
        public QuoteInput {
            if (rowNumber < 1) throw new IllegalArgumentException("rowNumber must be positive");
            if (supplierName == null || supplierName.isBlank()) {
                throw new IllegalArgumentException("供应商名称不能为空");
            }
            if (quoteAmount == null || quoteAmount.signum() < 0) {
                throw new IllegalArgumentException("报价金额必须是非负金额");
            }
        }

        QuoteInput normalized() {
            return new QuoteInput(rowNumber, supplierName.trim(),
                    quoteAmount.setScale(2, RoundingMode.HALF_UP),
                    productDescription == null || productDescription.isBlank()
                            ? null : productDescription.trim());
        }
    }

    public record Quote(UUID id, int rowNumber, String supplierName, BigDecimal quoteAmount,
                        String productDescription, boolean overBudget, BigDecimal overBudgetAmount,
                        BigDecimal overBudgetRatio, BigDecimal deviationRatio, boolean suspicious,
                        String suspiciousReason) {
    }
}
