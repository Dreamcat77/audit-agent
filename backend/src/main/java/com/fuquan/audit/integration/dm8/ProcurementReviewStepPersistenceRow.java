package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public class ProcurementReviewStepPersistenceRow {
    private String workflowId;
    private String stepCode;
    private Integer stepOrder;
    private String stepName;
    private String status;
    private LocalDateTime completedTime;
    private String resultSummary;
    public String getWorkflowId() { return workflowId; } public void setWorkflowId(String value) { workflowId = value; }
    public String getStepCode() { return stepCode; } public void setStepCode(String value) { stepCode = value; }
    public Integer getStepOrder() { return stepOrder; } public void setStepOrder(Integer value) { stepOrder = value; }
    public String getStepName() { return stepName; } public void setStepName(String value) { stepName = value; }
    public String getStatus() { return status; } public void setStatus(String value) { status = value; }
    public LocalDateTime getCompletedTime() { return completedTime; } public void setCompletedTime(LocalDateTime value) { completedTime = value; }
    public String getResultSummary() { return resultSummary; } public void setResultSummary(String value) { resultSummary = value; }
}
