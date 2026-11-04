package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * F01 — PROCUREMENT_DEMAND 表行映射。
 */
public class ProcurementDemandPersistenceRow {
    private String id;
    private String procurementName;
    private String budgetApprovalId;
    private String subjectCode;
    private String subjectName;
    private BigDecimal amount;
    private String supplierName;
    private String status;
    private LocalDateTime createdTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProcurementName() { return procurementName; }
    public void setProcurementName(String procurementName) { this.procurementName = procurementName; }
    public String getBudgetApprovalId() { return budgetApprovalId; }
    public void setBudgetApprovalId(String budgetApprovalId) { this.budgetApprovalId = budgetApprovalId; }
    public String getSubjectCode() { return subjectCode; }
    public void setSubjectCode(String subjectCode) { this.subjectCode = subjectCode; }
    public String getSubjectName() { return subjectName; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
}
