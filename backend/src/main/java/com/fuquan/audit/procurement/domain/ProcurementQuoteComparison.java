package com.fuquan.audit.procurement.domain;

import java.math.BigDecimal;
import java.util.*;

/** F06 like-for-like price advice. Eligibility is conditional until qualifications are confirmed. */
public record ProcurementQuoteComparison(String format, BigDecimal materialBudget,
        BigDecimal effectiveCeiling, List<String> conditions, List<Item> items,
        List<String> recommendedSuppliers, BigDecimal recommendedAmount, BigDecimal savingAmount,
        String recommendationStatus, List<String> notes, List<String> excludedSuppliers) {
    public ProcurementQuoteComparison {
        conditions = List.copyOf(conditions); items = List.copyOf(items);
        recommendedSuppliers = List.copyOf(recommendedSuppliers); notes = List.copyOf(notes);
        excludedSuppliers = List.copyOf(excludedSuppliers);
    }
    public record Offer(String supplierName, BigDecimal unitPrice, BigDecimal totalPrice) {}
    public record Item(int rowNumber, String itemCode, String itemName, String specification,
                       String unit, BigDecimal quantity, List<Offer> offers,
                       BigDecimal lowestAmount, List<String> lowestSuppliers) {
        public Item { offers = List.copyOf(offers); lowestSuppliers = List.copyOf(lowestSuppliers); }
    }

    public static ProcurementQuoteComparison advise(String format, BigDecimal materialBudget,
            BigDecimal projectBudget, List<String> conditions, List<Item> items,
            List<ProcurementQuoteCheck.QuoteInput> totals, Set<String> blocked) {
        BigDecimal ceiling = materialBudget == null ? projectBudget : projectBudget.min(materialBudget);
        List<String> notes = new ArrayList<>();
        if (materialBudget != null && materialBudget.compareTo(projectBudget) != 0)
            notes.add("材料预算上限与项目预算不同，价格建议按两者较低的上限筛选；项目预算未修改。");
        boolean comparable = "MATRIX".equals(format) ? !items.isEmpty() && items.stream().allMatch(i ->
                i.specification() != null && !i.specification().isBlank()) : totals.stream().allMatch(q ->
                q.productDescription() != null && !q.productDescription().isBlank()) &&
                totals.stream().map(q -> q.productDescription().replaceAll("\\s+", "").toLowerCase(Locale.ROOT)).distinct().count() == 1;
        long suppliers = totals.stream().map(ProcurementQuoteCheck.QuoteInput::supplierName).distinct().count();
        if (suppliers < 3) notes.add("有效供应商少于三家，需补充报价后完成三方询比价。");
        if (!comparable) notes.add("缺少一致的规格或完整采购范围，暂不能给出可比最低价建议。");
        notes.add("资质、规格实际响应和履约条件仍需核对；未命中黑名单不等于资质合格。");
        if (conditions.isEmpty()) notes.add("材料未明确税费、运费、安装及质保口径，请确认总价包含相同费用和服务。");
        if (!blocked.isEmpty()) notes.add("已排除命中现有黑名单库的供应商，请核对命中依据。");
        var eligible = comparable && suppliers >= 3 ? totals.stream().filter(q ->
                !blocked.contains(q.supplierName()) && q.quoteAmount().signum() > 0 &&
                q.quoteAmount().compareTo(ceiling) <= 0).toList() : List.<ProcurementQuoteCheck.QuoteInput>of();
        BigDecimal min = eligible.stream().map(ProcurementQuoteCheck.QuoteInput::quoteAmount).min(BigDecimal::compareTo).orElse(null);
        var recommended = eligible.stream().filter(q -> q.quoteAmount().compareTo(min) == 0)
                .map(ProcurementQuoteCheck.QuoteInput::supplierName).distinct().toList();
        if (comparable && suppliers >= 3 && min == null) notes.add("没有未命中黑名单且在预算上限内的完整报价，暂不推荐供应商。");
        return new ProcurementQuoteComparison(format, materialBudget, ceiling, conditions, items,
                recommended, min, min == null ? null : ceiling.subtract(min),
                min == null ? "PENDING" : "CONDITIONAL", notes, blocked.stream().sorted().toList());
    }
}
