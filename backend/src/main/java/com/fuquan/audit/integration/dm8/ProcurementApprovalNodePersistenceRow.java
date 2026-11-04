package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

/** PROCUREMENT_APPROVAL_NODE 表行映射。 */
public class ProcurementApprovalNodePersistenceRow {
    private String id;
    private String instanceId;
    private String nodeCode;
    private String nodeName;
    private Integer nodeOrder;
    private String status;
    private String requiredRoleCode;
    private String assigneeUserId;
    private String assigneeName;
    private LocalDateTime startedTime;
    private LocalDateTime completedTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getNodeCode() { return nodeCode; }
    public void setNodeCode(String nodeCode) { this.nodeCode = nodeCode; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public Integer getNodeOrder() { return nodeOrder; }
    public void setNodeOrder(Integer nodeOrder) { this.nodeOrder = nodeOrder; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRequiredRoleCode() { return requiredRoleCode; }
    public void setRequiredRoleCode(String requiredRoleCode) { this.requiredRoleCode = requiredRoleCode; }
    public String getAssigneeUserId() { return assigneeUserId; }
    public void setAssigneeUserId(String assigneeUserId) { this.assigneeUserId = assigneeUserId; }
    public String getAssigneeName() { return assigneeName; }
    public void setAssigneeName(String assigneeName) { this.assigneeName = assigneeName; }
    public LocalDateTime getStartedTime() { return startedTime; }
    public void setStartedTime(LocalDateTime startedTime) { this.startedTime = startedTime; }
    public LocalDateTime getCompletedTime() { return completedTime; }
    public void setCompletedTime(LocalDateTime completedTime) { this.completedTime = completedTime; }
}
