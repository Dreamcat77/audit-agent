package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * F03 BLACKLIST_ALERT 表行映射。
 */
public class BlacklistAlertPersistenceRow {
    private String id;
    private String batchId;
    private Integer rowNumber;
    private String procurementId;
    private String supplierName;
    private String supplierCode;
    private String blacklistId;
    private String matchedName;
    private String blacklistType;
    private String blacklistReason;
    private String matchType;
    private BigDecimal matchScore;
    private String alertLevel;
    private String status;
    private String handler;
    private String handleRemark;
    private LocalDateTime handleTime;
    private LocalDateTime createdTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public Integer getRowNumber() { return rowNumber; }
    public void setRowNumber(Integer rowNumber) { this.rowNumber = rowNumber; }
    public String getProcurementId() { return procurementId; }
    public void setProcurementId(String procurementId) { this.procurementId = procurementId; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getSupplierCode() { return supplierCode; }
    public void setSupplierCode(String supplierCode) { this.supplierCode = supplierCode; }
    public String getBlacklistId() { return blacklistId; }
    public void setBlacklistId(String blacklistId) { this.blacklistId = blacklistId; }
    public String getMatchedName() { return matchedName; }
    public void setMatchedName(String matchedName) { this.matchedName = matchedName; }
    public String getBlacklistType() { return blacklistType; }
    public void setBlacklistType(String blacklistType) { this.blacklistType = blacklistType; }
    public String getBlacklistReason() { return blacklistReason; }
    public void setBlacklistReason(String blacklistReason) { this.blacklistReason = blacklistReason; }
    public String getMatchType() { return matchType; }
    public void setMatchType(String matchType) { this.matchType = matchType; }
    public BigDecimal getMatchScore() { return matchScore; }
    public void setMatchScore(BigDecimal matchScore) { this.matchScore = matchScore; }
    public String getAlertLevel() { return alertLevel; }
    public void setAlertLevel(String alertLevel) { this.alertLevel = alertLevel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getHandler() { return handler; }
    public void setHandler(String handler) { this.handler = handler; }
    public String getHandleRemark() { return handleRemark; }
    public void setHandleRemark(String handleRemark) { this.handleRemark = handleRemark; }
    public LocalDateTime getHandleTime() { return handleTime; }
    public void setHandleTime(LocalDateTime handleTime) { this.handleTime = handleTime; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
}
