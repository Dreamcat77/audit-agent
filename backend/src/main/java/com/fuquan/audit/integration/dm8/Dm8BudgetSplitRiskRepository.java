package com.fuquan.audit.integration.dm8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.application.port.BudgetSplitRiskAssessmentRepository;
import com.fuquan.audit.budget.application.port.BudgetSplitRiskRuleRepository;
import com.fuquan.audit.budget.domain.BudgetSplitRiskAssessment;
import com.fuquan.audit.budget.domain.BudgetSplitRiskRecord;
import com.fuquan.audit.budget.domain.BudgetSplitRiskRule;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8BudgetSplitRiskRepository
        implements BudgetSplitRiskRuleRepository, BudgetSplitRiskAssessmentRepository {

    private final BudgetSplitRiskMapper mapper;
    private final ObjectMapper objectMapper;

    public Dm8BudgetSplitRiskRepository(BudgetSplitRiskMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<BudgetSplitRiskRule> findActive() {
        return mapper.findActiveRules().stream().map(row -> new BudgetSplitRiskRule(
                row.getRuleCode(), row.getRuleName(), row.getWindowDays(),
                row.getIndividualAmountLimit(), row.getAggregateAmountThreshold(),
                row.getMinimumRecordCount(), row.getWarningScore(), row.getCriticalScore(),
                row.getVersionNo())).toList();
    }

    @Override
    public void create(BudgetSplitRiskAssessment assessment) {
        BudgetSplitRiskAssessmentPersistenceRow row = new BudgetSplitRiskAssessmentPersistenceRow();
        row.setId(assessment.id().toString());
        row.setProcurementIdsJson(write(assessment.procurementIds()));
        row.setTotalAmount(assessment.totalAmount());
        row.setRiskLevel(assessment.riskLevel());
        row.setRiskScore(assessment.riskScore());
        row.setSplitRatio(assessment.splitRatio());
        row.setTimeConcentration(assessment.timeConcentration());
        row.setSupplierConcentration(assessment.supplierConcentration());
        row.setRecordsJson(write(assessment.records()));
        row.setCreatedBy(assessment.createdBy());
        row.setCreatedAt(java.time.LocalDateTime.ofInstant(assessment.createdAt(), ZoneOffset.UTC));
        mapper.insertAssessment(row);
    }

    @Override
    public Optional<BudgetSplitRiskAssessment> findById(UUID id) {
        BudgetSplitRiskAssessmentPersistenceRow row = mapper.findAssessmentById(id.toString());
        if (row == null) return Optional.empty();
        return Optional.of(new BudgetSplitRiskAssessment(
                UUID.fromString(row.getId()), read(row.getProcurementIdsJson(), new TypeReference<List<UUID>>() {}),
                row.getTotalAmount(), row.getRiskLevel(), row.getRiskScore(), row.getSplitRatio(),
                row.getTimeConcentration(), row.getSupplierConcentration(),
                read(row.getRecordsJson(), new TypeReference<List<BudgetSplitRiskRecord>>() {}),
                row.getCreatedBy(), row.getCreatedAt().toInstant(ZoneOffset.UTC)));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("F05 assessment serialization failed", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("F05 assessment snapshot is invalid", exception);
        }
    }
}
