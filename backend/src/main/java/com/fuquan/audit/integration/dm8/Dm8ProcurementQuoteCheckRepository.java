package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.procurement.application.port.ProcurementQuoteCheckRepository;
import com.fuquan.audit.procurement.domain.ProcurementQuoteCheck;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** F06 询比价分析 DM8 持久化适配器。 */
@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProcurementQuoteCheckRepository implements ProcurementQuoteCheckRepository {

    private final ProcurementQuoteCheckMapper mapper;

    public Dm8ProcurementQuoteCheckRepository(ProcurementQuoteCheckMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void save(ProcurementQuoteCheck check) {
        mapper.insertCheck(toCheckRow(check));
        List<ProcurementQuoteCheckItemPersistenceRow> rows = check.quotes().stream()
                .map(item -> toItemRow(check, item))
                .toList();
        if (!rows.isEmpty()) mapper.insertItems(rows);
        if (check.comparison() != null) {
            try { mapper.insertComparison(check.id().toString(), new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(check.comparison())); }
            catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("cannot encode comparison",ex); }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProcurementQuoteCheck> findByBatchId(String batchId) {
        ProcurementQuoteCheckPersistenceRow check = mapper.findLatestByBatchId(batchId);
        if (check == null) return Optional.empty();
        return Optional.of(read(check));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProcurementQuoteCheck> findById(UUID id) {
        var row = mapper.findById(id.toString());
        return row == null ? Optional.empty() : Optional.of(read(row));
    }
    private ProcurementQuoteCheck read(ProcurementQuoteCheckPersistenceRow row) {
        var check = toDomain(row, mapper.findItemsByCheckId(row.getId()));
        String json = mapper.findComparison(row.getId());
        if (json == null) return check;
        try { return check.withIdentityAndComparison(check.id(), new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                com.fuquan.audit.procurement.domain.ProcurementQuoteComparison.class)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("cannot read persisted comparison",ex); }
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplierQuoteTimeline> findLatestSupplierQuotesBySubject(
            String subjectCode, Instant fiscalYearStart, Instant fiscalYearEnd) {
        return mapper.findLatestSupplierQuotesBySubject(subjectCode,
                        java.time.LocalDateTime.ofInstant(fiscalYearStart, ZoneOffset.UTC),
                        java.time.LocalDateTime.ofInstant(fiscalYearEnd, ZoneOffset.UTC))
                .stream().map(row -> new SupplierQuoteTimeline(UUID.fromString(row.getProjectId()),
                        row.getProjectCode(), row.getProjectName(), row.getSupplierName(), row.getQuoteAmount(),
                        row.getQuoteTime().toInstant(ZoneOffset.UTC)))
                .toList();
    }

    private static ProcurementQuoteCheckPersistenceRow toCheckRow(ProcurementQuoteCheck check) {
        ProcurementQuoteCheckPersistenceRow row = new ProcurementQuoteCheckPersistenceRow();
        row.setId(check.id().toString());
        row.setBatchId(check.batchId());
        row.setBudgetAmount(check.budgetAmount());
        row.setAverageQuote(check.averageQuote());
        row.setStandardDeviation(check.standardDeviation());
        row.setCoefficientOfVariation(check.coefficientOfVariation());
        row.setHighlyConcentrated(check.highlyConcentrated() ? 1 : 0);
        row.setRiskLevel(check.riskLevel());
        row.setCreatedTime(java.time.LocalDateTime.ofInstant(check.createdTime(), ZoneOffset.UTC));
        return row;
    }

    private static ProcurementQuoteCheckItemPersistenceRow toItemRow(
            ProcurementQuoteCheck check, ProcurementQuoteCheck.Quote item) {
        ProcurementQuoteCheckItemPersistenceRow row = new ProcurementQuoteCheckItemPersistenceRow();
        row.setId(item.id().toString());
        row.setCheckId(check.id().toString());
        row.setBatchId(check.batchId());
        row.setRowNumber(item.rowNumber());
        row.setSupplierName(item.supplierName());
        row.setQuoteAmount(item.quoteAmount());
        row.setProductDescription(item.productDescription());
        row.setOverBudget(item.overBudget() ? 1 : 0);
        row.setOverBudgetAmount(item.overBudgetAmount());
        row.setOverBudgetRatio(item.overBudgetRatio());
        row.setDeviationRatio(item.deviationRatio());
        row.setSuspicious(item.suspicious() ? 1 : 0);
        row.setSuspiciousReason(item.suspiciousReason());
        row.setCreatedTime(java.time.LocalDateTime.ofInstant(check.createdTime(), ZoneOffset.UTC));
        return row;
    }

    private static ProcurementQuoteCheck toDomain(
            ProcurementQuoteCheckPersistenceRow row,
            List<ProcurementQuoteCheckItemPersistenceRow> items) {
        List<ProcurementQuoteCheck.Quote> quotes = items.stream()
                .map(item -> new ProcurementQuoteCheck.Quote(
                        UUID.fromString(item.getId()), item.getRowNumber(), item.getSupplierName(),
                        item.getQuoteAmount(), item.getProductDescription(), item.getOverBudget() == 1,
                        item.getOverBudgetAmount(), item.getOverBudgetRatio(), item.getDeviationRatio(),
                        item.getSuspicious() == 1, item.getSuspiciousReason()))
                .toList();
        return new ProcurementQuoteCheck(
                UUID.fromString(row.getId()), row.getBatchId(), row.getBudgetAmount(),
                row.getAverageQuote(), row.getStandardDeviation(), row.getCoefficientOfVariation(),
                row.getHighlyConcentrated() == 1, row.getRiskLevel(),
                row.getCreatedTime().toInstant(ZoneOffset.UTC), quotes);
    }
}
