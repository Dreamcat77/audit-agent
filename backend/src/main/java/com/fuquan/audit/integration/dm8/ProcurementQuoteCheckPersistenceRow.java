package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** F06 PROCUREMENT_QUOTE_CHECK 表行映射。 */
public class ProcurementQuoteCheckPersistenceRow {
    private String id;
    private String batchId;
    private BigDecimal budgetAmount;
    private BigDecimal averageQuote;
    private BigDecimal standardDeviation;
    private BigDecimal coefficientOfVariation;
    private Integer highlyConcentrated;
    private String riskLevel;
    private LocalDateTime createdTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public BigDecimal getBudgetAmount() { return budgetAmount; }
    public void setBudgetAmount(BigDecimal budgetAmount) { this.budgetAmount = budgetAmount; }
    public BigDecimal getAverageQuote() { return averageQuote; }
    public void setAverageQuote(BigDecimal averageQuote) { this.averageQuote = averageQuote; }
    public BigDecimal getStandardDeviation() { return standardDeviation; }
    public void setStandardDeviation(BigDecimal standardDeviation) { this.standardDeviation = standardDeviation; }
    public BigDecimal getCoefficientOfVariation() { return coefficientOfVariation; }
    public void setCoefficientOfVariation(BigDecimal coefficientOfVariation) { this.coefficientOfVariation = coefficientOfVariation; }
    public Integer getHighlyConcentrated() { return highlyConcentrated; }
    public void setHighlyConcentrated(Integer highlyConcentrated) { this.highlyConcentrated = highlyConcentrated; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
}
