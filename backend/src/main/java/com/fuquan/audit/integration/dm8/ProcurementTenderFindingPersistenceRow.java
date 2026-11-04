package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public class ProcurementTenderFindingPersistenceRow {
    private String id;
    private String projectId;
    private String reviewId;
    private String ruleCode;
    private String ruleName;
    private String riskCategory;
    private String riskLevel;
    private String matchedText;
    private String sourceLocator;
    private Integer pageNumber;
    private String remediationSuggestion;
    private String source;
    private String riskReason;
    private LocalDateTime createdTime;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String value) { projectId = value; }
    public String getReviewId() { return reviewId; }
    public void setReviewId(String value) { reviewId = value; }
    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String value) { ruleCode = value; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String value) { ruleName = value; }
    public String getRiskCategory() { return riskCategory; }
    public void setRiskCategory(String value) { riskCategory = value; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String value) { riskLevel = value; }
    public String getMatchedText() { return matchedText; }
    public void setMatchedText(String value) { matchedText = value; }
    public String getSourceLocator() { return sourceLocator; }
    public void setSourceLocator(String value) { sourceLocator = value; }
    public Integer getPageNumber() { return pageNumber; }
    public void setPageNumber(Integer value) { pageNumber = value; }
    public String getRemediationSuggestion() { return remediationSuggestion; }
    public void setRemediationSuggestion(String value) { remediationSuggestion = value; }
    public String getSource() { return source; }
    public void setSource(String value) { source = value; }
    public String getRiskReason() { return riskReason; }
    public void setRiskReason(String value) { riskReason = value; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime value) { createdTime = value; }
}
