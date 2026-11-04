package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Cross-project F06 quote timeline row used by F11 split-purchase detection. */
public class ProcurementSupplierQuoteTimelinePersistenceRow {
    private String projectId;
    private String projectCode;
    private String projectName;
    private String supplierName;
    private BigDecimal quoteAmount;
    private LocalDateTime quoteTime;

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getProjectCode() { return projectCode; }
    public void setProjectCode(String projectCode) { this.projectCode = projectCode; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public BigDecimal getQuoteAmount() { return quoteAmount; }
    public void setQuoteAmount(BigDecimal quoteAmount) { this.quoteAmount = quoteAmount; }
    public LocalDateTime getQuoteTime() { return quoteTime; }
    public void setQuoteTime(LocalDateTime quoteTime) { this.quoteTime = quoteTime; }
}
