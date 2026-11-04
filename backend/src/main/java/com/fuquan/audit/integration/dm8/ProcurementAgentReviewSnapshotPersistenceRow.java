package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public class ProcurementAgentReviewSnapshotPersistenceRow {
    private String id;
    private String projectId;
    private LocalDateTime generatedTime;
    private String reviewJson;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public LocalDateTime getGeneratedTime() { return generatedTime; }
    public void setGeneratedTime(LocalDateTime generatedTime) { this.generatedTime = generatedTime; }
    public String getReviewJson() { return reviewJson; }
    public void setReviewJson(String reviewJson) { this.reviewJson = reviewJson; }
}
