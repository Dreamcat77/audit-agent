package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public class ProcurementTenderRulePersistenceRow {
    private String id;
    private String ruleCode;
    private String ruleName;
    private String riskCategory;
    private String matchType;
    private String matchContent;
    private String riskLevel;
    private String remediationSuggestion;
    private String legalBasis;
    private String ruleExplanation;
    private String source;
    private Integer enabled;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String value) { ruleCode = value; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String value) { ruleName = value; }
    public String getRiskCategory() { return riskCategory; }
    public void setRiskCategory(String value) { riskCategory = value; }
    public String getMatchType() { return matchType; }
    public void setMatchType(String value) { matchType = value; }
    public String getMatchContent() { return matchContent; }
    public void setMatchContent(String value) { matchContent = value; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String value) { riskLevel = value; }
    public String getRemediationSuggestion() { return remediationSuggestion; }
    public void setRemediationSuggestion(String value) { remediationSuggestion = value; }
    public String getLegalBasis() { return legalBasis; }
    public void setLegalBasis(String value) { legalBasis = value; }
    public String getRuleExplanation() { return ruleExplanation; }
    public void setRuleExplanation(String value) { ruleExplanation = value; }
    public String getSource() { return source; }
    public void setSource(String value) { source = value; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer value) { enabled = value; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime value) { createdTime = value; }
    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime value) { updatedTime = value; }
}
