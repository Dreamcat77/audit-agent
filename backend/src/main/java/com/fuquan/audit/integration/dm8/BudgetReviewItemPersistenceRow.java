package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class BudgetReviewItemPersistenceRow {
    private String id;
    private String taskId;
    private Integer rowNumber;
    private String subjectCode;
    private String subjectName;
    private BigDecimal declaredAmount;
    private String supplierName;
    private String projectCode;
    private String approvalSubjectCode;
    private String approvalSubjectName;
    private BigDecimal approvalAmount;
    private String approvalSupplierName;
    private String matchStatus;
    private String matchMethod;
    private Integer matchDistance;
    private String amountStatus;
    private BigDecimal differenceAmount;
    private String exceptionType;
    private String errorDetail;
    private String riskLevel;
    private String missingFields;
    private String amountFormatErrors;
    private String codeFormatError;
    private String requiredFieldsStatus;
    private String amountFormatStatus;
    private String categoryAttributionStatus;
    private String categorySuggestion;
    private String projectCodeStatus;
    private String projectDepartmentStatus;
    private String budgetYearValidityStatus;
    private String abnormalReasons;
    private LocalDateTime createdTime;
    private String aiSuggestedCategory;
    private String aiHint;
    private String aiModel;
    private Long aiDurationMs;
    private Double aiConfidence;
    private String aiJudgment;
    private Double aiSimilarityScore;
    private String aiEnhancedDescription;
    private String aiSuggestion;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public Integer getRowNumber() { return rowNumber; }
    public void setRowNumber(Integer rowNumber) { this.rowNumber = rowNumber; }
    public String getSubjectCode() { return subjectCode; }
    public void setSubjectCode(String subjectCode) { this.subjectCode = subjectCode; }
    public String getSubjectName() { return subjectName; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }
    public BigDecimal getDeclaredAmount() { return declaredAmount; }
    public void setDeclaredAmount(BigDecimal declaredAmount) { this.declaredAmount = declaredAmount; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getProjectCode() { return projectCode; }
    public void setProjectCode(String projectCode) { this.projectCode = projectCode; }
    public String getApprovalSubjectCode() { return approvalSubjectCode; }
    public void setApprovalSubjectCode(String approvalSubjectCode) { this.approvalSubjectCode = approvalSubjectCode; }
    public String getApprovalSubjectName() { return approvalSubjectName; }
    public void setApprovalSubjectName(String approvalSubjectName) { this.approvalSubjectName = approvalSubjectName; }
    public BigDecimal getApprovalAmount() { return approvalAmount; }
    public void setApprovalAmount(BigDecimal approvalAmount) { this.approvalAmount = approvalAmount; }
    public String getApprovalSupplierName() { return approvalSupplierName; }
    public void setApprovalSupplierName(String approvalSupplierName) { this.approvalSupplierName = approvalSupplierName; }
    public String getMatchStatus() { return matchStatus; }
    public void setMatchStatus(String matchStatus) { this.matchStatus = matchStatus; }
    public String getMatchMethod() { return matchMethod; }
    public void setMatchMethod(String matchMethod) { this.matchMethod = matchMethod; }
    public Integer getMatchDistance() { return matchDistance; }
    public void setMatchDistance(Integer matchDistance) { this.matchDistance = matchDistance; }
    public String getAmountStatus() { return amountStatus; }
    public void setAmountStatus(String amountStatus) { this.amountStatus = amountStatus; }
    public BigDecimal getDifferenceAmount() { return differenceAmount; }
    public void setDifferenceAmount(BigDecimal differenceAmount) { this.differenceAmount = differenceAmount; }
    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public String getMissingFields() { return missingFields; }
    public void setMissingFields(String missingFields) { this.missingFields = missingFields; }
    public String getAmountFormatErrors() { return amountFormatErrors; }
    public void setAmountFormatErrors(String amountFormatErrors) { this.amountFormatErrors = amountFormatErrors; }
    public String getCodeFormatError() { return codeFormatError; }
    public void setCodeFormatError(String codeFormatError) { this.codeFormatError = codeFormatError; }
    public String getRequiredFieldsStatus() { return requiredFieldsStatus; }
    public void setRequiredFieldsStatus(String requiredFieldsStatus) { this.requiredFieldsStatus = requiredFieldsStatus; }
    public String getAmountFormatStatus() { return amountFormatStatus; }
    public void setAmountFormatStatus(String amountFormatStatus) { this.amountFormatStatus = amountFormatStatus; }
    public String getCategoryAttributionStatus() { return categoryAttributionStatus; }
    public void setCategoryAttributionStatus(String categoryAttributionStatus) { this.categoryAttributionStatus = categoryAttributionStatus; }
    public String getCategorySuggestion() { return categorySuggestion; }
    public void setCategorySuggestion(String categorySuggestion) { this.categorySuggestion = categorySuggestion; }
    public String getProjectCodeStatus() { return projectCodeStatus; }
    public void setProjectCodeStatus(String projectCodeStatus) { this.projectCodeStatus = projectCodeStatus; }
    public String getProjectDepartmentStatus() { return projectDepartmentStatus; }
    public void setProjectDepartmentStatus(String projectDepartmentStatus) { this.projectDepartmentStatus = projectDepartmentStatus; }
    public String getBudgetYearValidityStatus() { return budgetYearValidityStatus; }
    public void setBudgetYearValidityStatus(String budgetYearValidityStatus) { this.budgetYearValidityStatus = budgetYearValidityStatus; }
    public String getAbnormalReasons() { return abnormalReasons; }
    public void setAbnormalReasons(String abnormalReasons) { this.abnormalReasons = abnormalReasons; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
    public String getAiSuggestedCategory() { return aiSuggestedCategory; }
    public void setAiSuggestedCategory(String aiSuggestedCategory) { this.aiSuggestedCategory = aiSuggestedCategory; }
    public String getAiHint() { return aiHint; }
    public void setAiHint(String aiHint) { this.aiHint = aiHint; }
    public String getAiModel() { return aiModel; }
    public void setAiModel(String aiModel) { this.aiModel = aiModel; }
    public Long getAiDurationMs() { return aiDurationMs; }
    public void setAiDurationMs(Long aiDurationMs) { this.aiDurationMs = aiDurationMs; }
    public Double getAiConfidence() { return aiConfidence; }
    public void setAiConfidence(Double aiConfidence) { this.aiConfidence = aiConfidence; }
    public String getAiJudgment() { return aiJudgment; }
    public void setAiJudgment(String aiJudgment) { this.aiJudgment = aiJudgment; }
    public Double getAiSimilarityScore() { return aiSimilarityScore; }
    public void setAiSimilarityScore(Double aiSimilarityScore) { this.aiSimilarityScore = aiSimilarityScore; }
    public String getAiEnhancedDescription() { return aiEnhancedDescription; }
    public void setAiEnhancedDescription(String aiEnhancedDescription) { this.aiEnhancedDescription = aiEnhancedDescription; }
    public String getAiSuggestion() { return aiSuggestion; }
    public void setAiSuggestion(String aiSuggestion) { this.aiSuggestion = aiSuggestion; }
}
