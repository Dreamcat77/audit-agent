package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;

public class BudgetSplitRiskRulePersistenceRow {
    private String ruleCode;
    private String ruleName;
    private Integer windowDays;
    private BigDecimal individualAmountLimit;
    private BigDecimal aggregateAmountThreshold;
    private Integer minimumRecordCount;
    private Integer warningScore;
    private Integer criticalScore;
    private Integer versionNo;

    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public Integer getWindowDays() { return windowDays; }
    public void setWindowDays(Integer windowDays) { this.windowDays = windowDays; }
    public BigDecimal getIndividualAmountLimit() { return individualAmountLimit; }
    public void setIndividualAmountLimit(BigDecimal value) { this.individualAmountLimit = value; }
    public BigDecimal getAggregateAmountThreshold() { return aggregateAmountThreshold; }
    public void setAggregateAmountThreshold(BigDecimal value) { this.aggregateAmountThreshold = value; }
    public Integer getMinimumRecordCount() { return minimumRecordCount; }
    public void setMinimumRecordCount(Integer value) { this.minimumRecordCount = value; }
    public Integer getWarningScore() { return warningScore; }
    public void setWarningScore(Integer warningScore) { this.warningScore = warningScore; }
    public Integer getCriticalScore() { return criticalScore; }
    public void setCriticalScore(Integer criticalScore) { this.criticalScore = criticalScore; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
}
