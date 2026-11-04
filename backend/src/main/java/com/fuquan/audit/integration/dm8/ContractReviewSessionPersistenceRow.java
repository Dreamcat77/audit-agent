package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;

public class ContractReviewSessionPersistenceRow {
    private String id;
    private String contractId;
    private long contractVersion;
    private String name;
    private String reviewType;
    private String status;
    private String remark;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime materialsConfirmedAt;
    private long versionNo;
    private LocalDateTime deletedAt;
    private String deletedBy;
    private String sourceKind;
    private String projectId;

    public String getSourceKind() { return sourceKind; }
    public void setSourceKind(String sourceKind) { this.sourceKind = sourceKind; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public ContractReviewSessionPersistenceRow() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getContractId() { return contractId; }
    public void setContractId(String contractId) { this.contractId = contractId; }
    public long getContractVersion() { return contractVersion; }
    public void setContractVersion(long contractVersion) { this.contractVersion = contractVersion; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getReviewType() { return reviewType; }
    public void setReviewType(String reviewType) { this.reviewType = reviewType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getMaterialsConfirmedAt() { return materialsConfirmedAt; }
    public void setMaterialsConfirmedAt(LocalDateTime materialsConfirmedAt) { this.materialsConfirmedAt = materialsConfirmedAt; }
    public long getVersionNo() { return versionNo; }
    public void setVersionNo(long versionNo) { this.versionNo = versionNo; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public String getDeletedBy() { return deletedBy; }
    public void setDeletedBy(String deletedBy) { this.deletedBy = deletedBy; }
}
