package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public class ProcurementReviewWorkflowPersistenceRow {
    private String id;
    private String projectId;
    private Integer currentStepOrder;
    private String status;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    public String getId() { return id; } public void setId(String value) { id = value; }
    public String getProjectId() { return projectId; } public void setProjectId(String value) { projectId = value; }
    public Integer getCurrentStepOrder() { return currentStepOrder; } public void setCurrentStepOrder(Integer value) { currentStepOrder = value; }
    public String getStatus() { return status; } public void setStatus(String value) { status = value; }
    public LocalDateTime getCreatedTime() { return createdTime; } public void setCreatedTime(LocalDateTime value) { createdTime = value; }
    public LocalDateTime getUpdatedTime() { return updatedTime; } public void setUpdatedTime(LocalDateTime value) { updatedTime = value; }
}
