package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public class BudgetValidationConfirmLogPersistenceRow {
    private Long id;
    private String batchId;
    private Integer rowNumber;
    private String departmentCode;
    private String departmentName;
    private String confirmerUserId;
    private String confirmerName;
    private LocalDateTime confirmTime;
    private String confirmStep;
    private String abnormalReason;
    private String remark;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public Integer getRowNumber() { return rowNumber; }
    public void setRowNumber(Integer rowNumber) { this.rowNumber = rowNumber; }
    public String getDepartmentCode() { return departmentCode; }
    public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
    public String getDepartmentName() { return departmentName; }
    public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }
    public String getConfirmerUserId() { return confirmerUserId; }
    public void setConfirmerUserId(String confirmerUserId) { this.confirmerUserId = confirmerUserId; }
    public String getConfirmerName() { return confirmerName; }
    public void setConfirmerName(String confirmerName) { this.confirmerName = confirmerName; }
    public LocalDateTime getConfirmTime() { return confirmTime; }
    public void setConfirmTime(LocalDateTime confirmTime) { this.confirmTime = confirmTime; }
    public String getConfirmStep() { return confirmStep; }
    public void setConfirmStep(String confirmStep) { this.confirmStep = confirmStep; }
    public String getAbnormalReason() { return abnormalReason; }
    public void setAbnormalReason(String abnormalReason) { this.abnormalReason = abnormalReason; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
