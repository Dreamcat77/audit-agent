package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

/**
 * F01 — BUDGET_APPROVAL_CHECK 表行映射。
 */
public class BudgetApprovalCheckPersistenceRow {
    private String id;
    private String procurementId;
    private String checkType;
    private String subjectCode;
    private String expectedValue;
    private String actualValue;
    private Boolean passed;
    private String errorDetail;
    private LocalDateTime checkTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProcurementId() { return procurementId; }
    public void setProcurementId(String procurementId) { this.procurementId = procurementId; }
    public String getCheckType() { return checkType; }
    public void setCheckType(String checkType) { this.checkType = checkType; }
    public String getSubjectCode() { return subjectCode; }
    public void setSubjectCode(String subjectCode) { this.subjectCode = subjectCode; }
    public String getExpectedValue() { return expectedValue; }
    public void setExpectedValue(String expectedValue) { this.expectedValue = expectedValue; }
    public String getActualValue() { return actualValue; }
    public void setActualValue(String actualValue) { this.actualValue = actualValue; }
    public Boolean getPassed() { return passed; }
    public void setPassed(Boolean passed) { this.passed = passed; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }
    public LocalDateTime getCheckTime() { return checkTime; }
    public void setCheckTime(LocalDateTime checkTime) { this.checkTime = checkTime; }
}
