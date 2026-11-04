package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class ContractPersistenceRow {
    private String id;
    private String contractNo;
    private String contractName;
    private String contractType;
    private String partyA;
    private String partyB;
    private String partyBCreditCode;
    private BigDecimal contractAmount;
    private String amountInWords;
    private String status;
    private long versionNo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
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
    public void setPartyBCreditCode(String partyBCreditCode) { this.partyBCreditCode = partyBCreditCode; }
    public BigDecimal getContractAmount() { return contractAmount; }
    public void setContractAmount(BigDecimal contractAmount) { this.contractAmount = contractAmount; }
    public String getAmountInWords() { return amountInWords; }
    public void setAmountInWords(String amountInWords) { this.amountInWords = amountInWords; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getVersionNo() { return versionNo; }
    public void setVersionNo(long versionNo) { this.versionNo = versionNo; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
