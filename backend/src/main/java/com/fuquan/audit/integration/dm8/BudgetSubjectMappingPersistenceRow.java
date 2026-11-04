package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * F01 — BUDGET_SUBJECT_MAPPING 表行映射。
 */
public class BudgetSubjectMappingPersistenceRow {
    private String id;
    private String systemSubjectCode;
    private String systemSubjectName;
    private String budgetSubjectCode;
    private String budgetSubjectName;
    private BigDecimal budgetAmount;
    private String budgetYear;
    private String supplierName;
    private String status;
    private LocalDateTime createTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSystemSubjectCode() { return systemSubjectCode; }
    public void setSystemSubjectCode(String systemSubjectCode) { this.systemSubjectCode = systemSubjectCode; }
    public String getSystemSubjectName() { return systemSubjectName; }
    public void setSystemSubjectName(String systemSubjectName) { this.systemSubjectName = systemSubjectName; }
    public String getBudgetSubjectCode() { return budgetSubjectCode; }
    public void setBudgetSubjectCode(String budgetSubjectCode) { this.budgetSubjectCode = budgetSubjectCode; }
    public String getBudgetSubjectName() { return budgetSubjectName; }
    public void setBudgetSubjectName(String budgetSubjectName) { this.budgetSubjectName = budgetSubjectName; }
    public BigDecimal getBudgetAmount() { return budgetAmount; }
    public void setBudgetAmount(BigDecimal budgetAmount) { this.budgetAmount = budgetAmount; }
    public String getBudgetYear() { return budgetYear; }
    public void setBudgetYear(String budgetYear) { this.budgetYear = budgetYear; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
