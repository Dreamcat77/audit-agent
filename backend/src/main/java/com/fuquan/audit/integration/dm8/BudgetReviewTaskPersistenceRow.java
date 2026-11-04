package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public class BudgetReviewTaskPersistenceRow {
    private String id;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private String tempFilePath;
    private String status;
    private Integer parsedLineCount;
    private String errorDetail;
    private String budgetYear;
    private String departmentCode;
    private Integer reviewCursor;
    private Integer pendingRowNumber;
    private String pendingStep;
    private String pendingReason;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getTempFilePath() { return tempFilePath; }
    public void setTempFilePath(String tempFilePath) { this.tempFilePath = tempFilePath; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getParsedLineCount() { return parsedLineCount; }
    public void setParsedLineCount(Integer parsedLineCount) { this.parsedLineCount = parsedLineCount; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }
    public String getBudgetYear() { return budgetYear; }
    public void setBudgetYear(String budgetYear) { this.budgetYear = budgetYear; }
    public String getDepartmentCode() { return departmentCode; }
    public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
    public Integer getReviewCursor() { return reviewCursor; }
    public void setReviewCursor(Integer reviewCursor) { this.reviewCursor = reviewCursor; }
    public Integer getPendingRowNumber() { return pendingRowNumber; }
    public void setPendingRowNumber(Integer pendingRowNumber) { this.pendingRowNumber = pendingRowNumber; }
    public String getPendingStep() { return pendingStep; }
    public void setPendingStep(String pendingStep) { this.pendingStep = pendingStep; }
    public String getPendingReason() { return pendingReason; }
    public void setPendingReason(String pendingReason) { this.pendingReason = pendingReason; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
}
