package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

/** DM8 persistence projection for the latest F11 split-purchase assessment. */
public class ProcurementSplitPurchaseAssessmentPersistenceRow {
    private String id;
    private String projectId;
    private String riskLevel;
    private Integer riskScore;
    private String findingsJson;
    private LocalDateTime assessedTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public Integer getRiskScore() { return riskScore; }
    public void setRiskScore(Integer riskScore) { this.riskScore = riskScore; }
    public String getFindingsJson() { return findingsJson; }
    public void setFindingsJson(String findingsJson) { this.findingsJson = findingsJson; }
    public LocalDateTime getAssessedTime() { return assessedTime; }
    public void setAssessedTime(LocalDateTime assessedTime) { this.assessedTime = assessedTime; }
}
