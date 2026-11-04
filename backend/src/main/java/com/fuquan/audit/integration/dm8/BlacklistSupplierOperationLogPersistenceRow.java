package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

/**
 * F03 BLACKLIST_SUPPLIER_OPERATION_LOG 表行映射。
 */
public class BlacklistSupplierOperationLogPersistenceRow {
    private String id;
    private String blacklistId;
    private String operationType;
    private String operatorUserId;
    private String operatorName;
    private String operationReason;
    private LocalDateTime operatedTime;
    private LocalDateTime createdTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getBlacklistId() { return blacklistId; }
    public void setBlacklistId(String blacklistId) { this.blacklistId = blacklistId; }
    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }
    public String getOperatorUserId() { return operatorUserId; }
    public void setOperatorUserId(String operatorUserId) { this.operatorUserId = operatorUserId; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public String getOperationReason() { return operationReason; }
    public void setOperationReason(String operationReason) { this.operationReason = operationReason; }
    public LocalDateTime getOperatedTime() { return operatedTime; }
    public void setOperatedTime(LocalDateTime operatedTime) { this.operatedTime = operatedTime; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
}
