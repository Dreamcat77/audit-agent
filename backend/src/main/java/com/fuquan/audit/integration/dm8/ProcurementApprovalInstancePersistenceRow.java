package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

/** PROCUREMENT_APPROVAL_INSTANCE 表行映射。 */
public class ProcurementApprovalInstancePersistenceRow {
    private String id;
    private String projectId;
    private String status;
    private String currentNodeCode;
    private String startedByUserId;
    private String startedByName;
    private LocalDateTime startedTime;
    private LocalDateTime completedTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    private String returnSnapshot;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCurrentNodeCode() { return currentNodeCode; }
    public void setCurrentNodeCode(String currentNodeCode) { this.currentNodeCode = currentNodeCode; }
    public String getStartedByUserId() { return startedByUserId; }
    public void setStartedByUserId(String startedByUserId) { this.startedByUserId = startedByUserId; }
    public String getStartedByName() { return startedByName; }
    public void setStartedByName(String startedByName) { this.startedByName = startedByName; }
    public LocalDateTime getStartedTime() { return startedTime; }
    public void setStartedTime(LocalDateTime startedTime) { this.startedTime = startedTime; }
    public LocalDateTime getCompletedTime() { return completedTime; }
    public void setCompletedTime(LocalDateTime completedTime) { this.completedTime = completedTime; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
    public String getReturnSnapshot() { return returnSnapshot; }
    public void setReturnSnapshot(String returnSnapshot) { this.returnSnapshot = returnSnapshot; }
}
