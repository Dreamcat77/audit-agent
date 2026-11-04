package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

/** PROCUREMENT_APPROVAL_OPERATION_LOG 表行映射。 */
public class ProcurementApprovalOperationPersistenceRow {
    private String id;
    private String instanceId;
    private String projectId;
    private String nodeCode;
    private String operationType;
    private String operatorUserId;
    private String operatorName;
    private String opinion;
    private String fromStatus;
    private String toStatus;
    private LocalDateTime operatedTime;
    private LocalDateTime createdTime;
    private String originalAssigneeUserId;
    private String originalAssigneeName;
    private String newAssigneeUserId;
    private String newAssigneeName;
    private Integer adminActing;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getNodeCode() { return nodeCode; }
    public void setNodeCode(String nodeCode) { this.nodeCode = nodeCode; }
    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }
    public String getOperatorUserId() { return operatorUserId; }
    public void setOperatorUserId(String operatorUserId) { this.operatorUserId = operatorUserId; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public String getOpinion() { return opinion; }
    public void setOpinion(String opinion) { this.opinion = opinion; }
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public LocalDateTime getOperatedTime() { return operatedTime; }
    public void setOperatedTime(LocalDateTime operatedTime) { this.operatedTime = operatedTime; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
    public String getOriginalAssigneeUserId() { return originalAssigneeUserId; }
    public void setOriginalAssigneeUserId(String value) { this.originalAssigneeUserId = value; }
    public String getOriginalAssigneeName() { return originalAssigneeName; }
    public void setOriginalAssigneeName(String value) { this.originalAssigneeName = value; }
    public String getNewAssigneeUserId() { return newAssigneeUserId; }
    public void setNewAssigneeUserId(String value) { this.newAssigneeUserId = value; }
    public String getNewAssigneeName() { return newAssigneeName; }
    public void setNewAssigneeName(String value) { this.newAssigneeName = value; }
    public Integer getAdminActing() { return adminActing; }
    public void setAdminActing(Integer value) { this.adminActing = value; }
}
