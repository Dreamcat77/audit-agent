package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** F06 PROCUREMENT_QUOTE_CHECK_ITEM 表行映射。 */
public class ProcurementQuoteCheckItemPersistenceRow {
    private String id;
    private String checkId;
    private String batchId;
    private Integer rowNumber;
    private String supplierName;
    private BigDecimal quoteAmount;
    private String productDescription;
    private Integer overBudget;
    private BigDecimal overBudgetAmount;
    private BigDecimal overBudgetRatio;
    private BigDecimal deviationRatio;
    private Integer suspicious;
    private String suspiciousReason;
    private LocalDateTime createdTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCheckId() { return checkId; }
    public void setCheckId(String checkId) { this.checkId = checkId; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public Integer getRowNumber() { return rowNumber; }
    public void setRowNumber(Integer rowNumber) { this.rowNumber = rowNumber; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public BigDecimal getQuoteAmount() { return quoteAmount; }
    public void setQuoteAmount(BigDecimal quoteAmount) { this.quoteAmount = quoteAmount; }
    public String getProductDescription() { return productDescription; }
    public void setProductDescription(String productDescription) { this.productDescription = productDescription; }
    public Integer getOverBudget() { return overBudget; }
    public void setOverBudget(Integer overBudget) { this.overBudget = overBudget; }
    public BigDecimal getOverBudgetAmount() { return overBudgetAmount; }
    public void setOverBudgetAmount(BigDecimal overBudgetAmount) { this.overBudgetAmount = overBudgetAmount; }
    public BigDecimal getOverBudgetRatio() { return overBudgetRatio; }
    public void setOverBudgetRatio(BigDecimal overBudgetRatio) { this.overBudgetRatio = overBudgetRatio; }
    public BigDecimal getDeviationRatio() { return deviationRatio; }
    public void setDeviationRatio(BigDecimal deviationRatio) { this.deviationRatio = deviationRatio; }
    public Integer getSuspicious() { return suspicious; }
    public void setSuspicious(Integer suspicious) { this.suspicious = suspicious; }
    public String getSuspiciousReason() { return suspiciousReason; }
    public void setSuspiciousReason(String suspiciousReason) { this.suspiciousReason = suspiciousReason; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
}
