package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.budget.application.port.BudgetReviewItemRepository;
import com.fuquan.audit.budget.domain.BudgetReviewItem;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Arrays;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8BudgetReviewItemRepository implements BudgetReviewItemRepository {

    private final BudgetReviewItemMapper mapper;

    public Dm8BudgetReviewItemRepository(BudgetReviewItemMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void saveAll(List<BudgetReviewItem> items) {
        if (items.isEmpty()) {
            return;
        }
        List<BudgetReviewItemPersistenceRow> rows = new ArrayList<>(items.size());
        for (BudgetReviewItem item : items) {
            rows.add(toRow(item));
        }
        mapper.insertBatch(rows);
    }

    @Override
    @Transactional
    public void deleteByTaskAndRow(UUID taskId, int rowNumber) {
        mapper.deleteByTaskAndRow(taskId.toString(), rowNumber);
    }

    @Override
    public List<BudgetReviewItem> findByTaskId(UUID taskId) {
        return mapper.findByTaskId(taskId.toString()).stream().map(Dm8BudgetReviewItemRepository::toDomain).toList();
    }

    @Override
    public Optional<BudgetReviewItem> findById(UUID id) {
        BudgetReviewItemPersistenceRow row = mapper.findById(id.toString());
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    private static BudgetReviewItemPersistenceRow toRow(BudgetReviewItem item) {
        BudgetReviewItemPersistenceRow row = new BudgetReviewItemPersistenceRow();
        row.setId(item.id().toString());
        row.setTaskId(item.taskId().toString());
        row.setRowNumber(item.rowNumber());
        row.setSubjectCode(item.subjectCode());
        row.setSubjectName(item.subjectName());
        row.setDeclaredAmount(item.declaredAmount());
        row.setSupplierName(item.supplierName());
        row.setProjectCode(item.projectCode());
        row.setApprovalSubjectCode(item.approvalSubjectCode());
        row.setApprovalSubjectName(item.approvalSubjectName());
        row.setApprovalAmount(item.approvalAmount());
        row.setApprovalSupplierName(item.approvalSupplierName());
        row.setMatchStatus(item.matchStatus());
        row.setMatchMethod(item.matchMethod());
        row.setMatchDistance(item.matchDistance());
        row.setAmountStatus(item.amountStatus());
        row.setDifferenceAmount(item.differenceAmount());
        row.setExceptionType(item.exceptionType());
        row.setErrorDetail(item.errorDetail());
        row.setRiskLevel(item.riskLevel());
        row.setMissingFields(item.missingFields());
        row.setAmountFormatErrors(item.amountFormatErrors());
        row.setCodeFormatError(item.codeFormatError());
        row.setRequiredFieldsStatus(item.requiredFieldsStatus());
        row.setAmountFormatStatus(item.amountFormatStatus());
        row.setCategoryAttributionStatus(item.categoryAttributionStatus());
        row.setCategorySuggestion(item.categorySuggestion());
        row.setProjectCodeStatus(item.projectCodeStatus());
        row.setProjectDepartmentStatus(item.projectDepartmentStatus());
        row.setBudgetYearValidityStatus(item.budgetYearValidityStatus());
        row.setAbnormalReasons(item.abnormalReasons().isEmpty()
                ? null : String.join(",", item.abnormalReasons()));
        row.setCreatedTime(java.time.LocalDateTime.ofInstant(item.createdTime(), ZoneOffset.UTC));
        row.setAiSuggestedCategory(item.aiSuggestedCategory());
        row.setAiHint(item.aiHint());
        row.setAiModel(item.aiModel());
        row.setAiDurationMs(item.aiDurationMs());
        row.setAiConfidence(item.aiConfidence());
        row.setAiJudgment(item.aiJudgment());
        row.setAiSimilarityScore(item.aiSimilarityScore());
        row.setAiEnhancedDescription(item.aiEnhancedDescription());
        row.setAiSuggestion(item.aiSuggestion());
        return row;
    }

    private static BudgetReviewItem toDomain(BudgetReviewItemPersistenceRow row) {
        return new BudgetReviewItem(
                UUID.fromString(row.getId()),
                UUID.fromString(row.getTaskId()),
                row.getRowNumber(),
                row.getSubjectCode(),
                row.getSubjectName(),
                row.getDeclaredAmount(),
                row.getSupplierName(),
                row.getProjectCode(),
                row.getApprovalSubjectCode(),
                row.getApprovalSubjectName(),
                row.getApprovalAmount(),
                row.getApprovalSupplierName(),
                row.getMatchStatus(),
                row.getMatchMethod(),
                row.getMatchDistance(),
                row.getAmountStatus(),
                row.getDifferenceAmount(),
                row.getExceptionType(),
                row.getErrorDetail(),
                row.getRiskLevel() == null ? "INFO" : row.getRiskLevel(),
                row.getMissingFields(),
                row.getAmountFormatErrors(),
                row.getCodeFormatError(),
                row.getRequiredFieldsStatus() == null ? "NOT_CHECKED" : row.getRequiredFieldsStatus(),
                row.getAmountFormatStatus() == null ? "NOT_CHECKED" : row.getAmountFormatStatus(),
                row.getCategoryAttributionStatus() == null ? "NOT_CHECKED" : row.getCategoryAttributionStatus(),
                row.getCategorySuggestion(),
                row.getProjectCodeStatus() == null ? "NOT_CHECKED" : row.getProjectCodeStatus(),
                row.getProjectDepartmentStatus() == null ? "NOT_CHECKED" : row.getProjectDepartmentStatus(),
                row.getBudgetYearValidityStatus() == null ? "NOT_CHECKED" : row.getBudgetYearValidityStatus(),
                row.getAbnormalReasons() == null || row.getAbnormalReasons().isBlank()
                        ? java.util.List.of() : Arrays.asList(row.getAbnormalReasons().split(",", -1)),
                row.getCreatedTime().toInstant(ZoneOffset.UTC),
                row.getAiSuggestedCategory(), row.getAiHint(),
                row.getAiModel(), row.getAiDurationMs(), row.getAiConfidence(),
                row.getAiJudgment(), row.getAiSimilarityScore(),
                row.getAiEnhancedDescription(), row.getAiSuggestion());
    }
}
