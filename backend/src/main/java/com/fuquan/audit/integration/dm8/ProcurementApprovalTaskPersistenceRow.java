package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** DM8 查询出的审批待办投影。 */
public class ProcurementApprovalTaskPersistenceRow {
    private String projectId;
    private String projectCode;
    private String projectName;
    private BigDecimal budgetAmount;
    private String instanceId;
    private String instanceStatus;
    private String nodeId;
    private String nodeCode;
    private String nodeName;
    private String requiredRoleCode;
    private String assigneeUserId;
    private String assigneeName;
    private LocalDateTime startedTime;

    public String getProjectId() { return projectId; }
    public void setProjectId(String value) { this.projectId = value; }
    public String getProjectCode() { return projectCode; }
    public void setProjectCode(String value) { this.projectCode = value; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String value) { this.projectName = value; }
    public BigDecimal getBudgetAmount() { return budgetAmount; }
    public void setBudgetAmount(BigDecimal value) { this.budgetAmount = value; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String value) { this.instanceId = value; }
    public String getInstanceStatus() { return instanceStatus; }
    public void setInstanceStatus(String value) { this.instanceStatus = value; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String value) { this.nodeId = value; }
    public String getNodeCode() { return nodeCode; }
    public void setNodeCode(String value) { this.nodeCode = value; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String value) { this.nodeName = value; }
    public String getRequiredRoleCode() { return requiredRoleCode; }
    public void setRequiredRoleCode(String value) { this.requiredRoleCode = value; }
    public String getAssigneeUserId() { return assigneeUserId; }
    public void setAssigneeUserId(String value) { this.assigneeUserId = value; }
    public String getAssigneeName() { return assigneeName; }
    public void setAssigneeName(String value) { this.assigneeName = value; }
    public LocalDateTime getStartedTime() { return startedTime; }
    public void setStartedTime(LocalDateTime value) { this.startedTime = value; }
}
