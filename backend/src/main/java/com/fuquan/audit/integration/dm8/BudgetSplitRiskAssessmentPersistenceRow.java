package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class BudgetSplitRiskAssessmentPersistenceRow {
    private String id;
    private String procurementIdsJson;
    private BigDecimal totalAmount;
    private String riskLevel;
    private Integer riskScore;
    private BigDecimal splitRatio;
    private BigDecimal timeConcentration;
    private BigDecimal supplierConcentration;
    private String recordsJson;
    private String createdBy;
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProcurementIdsJson() { return procurementIdsJson; }
    public void setProcurementIdsJson(String value) { this.procurementIdsJson = value; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public Integer getRiskScore() { return riskScore; }
    public void setRiskScore(Integer riskScore) { this.riskScore = riskScore; }
    public BigDecimal getSplitRatio() { return splitRatio; }
    public void setSplitRatio(BigDecimal splitRatio) { this.splitRatio = splitRatio; }
    public BigDecimal getTimeConcentration() { return timeConcentration; }
    public void setTimeConcentration(BigDecimal value) { this.timeConcentration = value; }
    public BigDecimal getSupplierConcentration() { return supplierConcentration; }
    public void setSupplierConcentration(BigDecimal value) { this.supplierConcentration = value; }
    public String getRecordsJson() { return recordsJson; }
    public void setRecordsJson(String recordsJson) { this.recordsJson = recordsJson; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
