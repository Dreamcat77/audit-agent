package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public class ProcurementReviewReportPersistenceRow {
    private String id;
    private String projectId;
    private String reportNumber;
    private String riskLevel;
    private LocalDateTime generatedTime;
    private String overallConclusion;
    private String agentReviewJson;
    private String reportSnapshotJson;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getReportNumber() { return reportNumber; }
    public void setReportNumber(String reportNumber) { this.reportNumber = reportNumber; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public LocalDateTime getGeneratedTime() { return generatedTime; }
    public void setGeneratedTime(LocalDateTime generatedTime) { this.generatedTime = generatedTime; }
    public String getOverallConclusion() { return overallConclusion; }
    public void setOverallConclusion(String overallConclusion) { this.overallConclusion = overallConclusion; }
    public String getAgentReviewJson() { return agentReviewJson; }
    public void setAgentReviewJson(String agentReviewJson) { this.agentReviewJson = agentReviewJson; }
    public String getReportSnapshotJson() { return reportSnapshotJson; }
    public void setReportSnapshotJson(String value) { reportSnapshotJson = value; }
}
