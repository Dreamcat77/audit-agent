package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One joined ReviewSession/Contract query row. */
public class ReviewSessionTaskPersistenceRow {
    private String sessionId;
    private String name;
    private String reviewType;
    private String sessionStatus;
    private String remark;
    private long contractVersion;
    private String createdBy;
    private String createdByName;
    private LocalDateTime sessionCreatedAt;
    private LocalDateTime sessionUpdatedAt;
    private LocalDateTime materialsConfirmedAt;
    private long sessionVersionNo;
    private LocalDateTime deletedAt;
    private String deletedBy;
    private String contractId;
    private String contractNo;
    private String contractName;
    private String contractType;
    private String partyA;
    private String partyB;
    private String partyBCreditCode;
    private BigDecimal contractAmount;
    private String amountInWords;
    private String contractStatus;
    private long contractVersionNo;
    private LocalDateTime contractCreatedAt;
    private LocalDateTime contractUpdatedAt;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getReviewType() { return reviewType; }
    public void setReviewType(String reviewType) { this.reviewType = reviewType; }
    public String getSessionStatus() { return sessionStatus; }
    public void setSessionStatus(String sessionStatus) { this.sessionStatus = sessionStatus; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public long getContractVersion() { return contractVersion; }
    public void setContractVersion(long contractVersion) { this.contractVersion = contractVersion; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public LocalDateTime getSessionCreatedAt() { return sessionCreatedAt; }
    public void setSessionCreatedAt(LocalDateTime value) { this.sessionCreatedAt = value; }
    public LocalDateTime getSessionUpdatedAt() { return sessionUpdatedAt; }
    public void setSessionUpdatedAt(LocalDateTime value) { this.sessionUpdatedAt = value; }
    public LocalDateTime getMaterialsConfirmedAt() { return materialsConfirmedAt; }
    public void setMaterialsConfirmedAt(LocalDateTime value) { this.materialsConfirmedAt = value; }
    public long getSessionVersionNo() { return sessionVersionNo; }
    public void setSessionVersionNo(long sessionVersionNo) { this.sessionVersionNo = sessionVersionNo; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public String getDeletedBy() { return deletedBy; }
    public void setDeletedBy(String deletedBy) { this.deletedBy = deletedBy; }
    public String getContractId() { return contractId; }
    public void setContractId(String contractId) { this.contractId = contractId; }
    public String getContractNo() { return contractNo; }
    public void setContractNo(String contractNo) { this.contractNo = contractNo; }
    public String getContractName() { return contractName; }
    public void setContractName(String contractName) { this.contractName = contractName; }
    public String getContractType() { return contractType; }
    public void setContractType(String contractType) { this.contractType = contractType; }
    public String getPartyA() { return partyA; }
    public void setPartyA(String partyA) { this.partyA = partyA; }
    public String getPartyB() { return partyB; }
    public void setPartyB(String partyB) { this.partyB = partyB; }
    public String getPartyBCreditCode() { return partyBCreditCode; }
    public void setPartyBCreditCode(String value) { this.partyBCreditCode = value; }
    public BigDecimal getContractAmount() { return contractAmount; }
    public void setContractAmount(BigDecimal contractAmount) { this.contractAmount = contractAmount; }
    public String getAmountInWords() { return amountInWords; }
    public void setAmountInWords(String amountInWords) { this.amountInWords = amountInWords; }
    public String getContractStatus() { return contractStatus; }
    public void setContractStatus(String contractStatus) { this.contractStatus = contractStatus; }
    public long getContractVersionNo() { return contractVersionNo; }
    public void setContractVersionNo(long value) { this.contractVersionNo = value; }
    public LocalDateTime getContractCreatedAt() { return contractCreatedAt; }
    public void setContractCreatedAt(LocalDateTime value) { this.contractCreatedAt = value; }
    public LocalDateTime getContractUpdatedAt() { return contractUpdatedAt; }
    public void setContractUpdatedAt(LocalDateTime value) { this.contractUpdatedAt = value; }
}
