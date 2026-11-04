package com.fuquan.audit.integration.dm8;

import java.time.LocalDateTime;
import java.math.BigDecimal;

public class MaterialIntakePersistenceRow {
    private String id;
    private String actorId;
    private String operationKey;
    private String fileName;
    private String mediaType;
    private long fileSize;
    private String sha256;
    private String storageKey;
    private String status;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private String fieldsJson;
    private String parseMethod;
    private String errorCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String projectName;
    private String projectNo;
    private String departmentName;
    private String procurementType;
    private BigDecimal budgetAmount;
    private String subjectCode;
    private String subjectName;
    private String procurementMethod;
    private String requirementDescription;
    private String applicantName;
    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getActorId() { return actorId; }
    public void setActorId(String value) { actorId = value; }
    public String getOperationKey() { return operationKey; }
    public void setOperationKey(String value) { operationKey = value; }
    public String getFileName() { return fileName; }
    public void setFileName(String value) { fileName = value; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String value) { mediaType = value; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long value) { fileSize = value; }
    public String getSha256() { return sha256; }
    public void setSha256(String value) { sha256 = value; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String value) { storageKey = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String value) { leaseToken = value; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime value) { leaseUntil = value; }
    public String getFieldsJson() { return fieldsJson; }
    public void setFieldsJson(String value) { fieldsJson = value; }
    public String getParseMethod() { return parseMethod; }
    public void setParseMethod(String value) { parseMethod = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String value) { projectName = value; }
    public String getProjectNo() { return projectNo; }
    public void setProjectNo(String value) { projectNo = value; }
    public String getDepartmentName() { return departmentName; }
    public void setDepartmentName(String value) { departmentName = value; }
    public String getProcurementType() { return procurementType; }
    public void setProcurementType(String value) { procurementType = value; }
    public BigDecimal getBudgetAmount() { return budgetAmount; }
    public void setBudgetAmount(BigDecimal value) { budgetAmount = value; }
    public String getSubjectCode() { return subjectCode; }
    public void setSubjectCode(String value) { subjectCode = value; }
    public String getSubjectName() { return subjectName; }
    public void setSubjectName(String value) { subjectName = value; }
    public String getProcurementMethod() { return procurementMethod; }
    public void setProcurementMethod(String value) { procurementMethod = value; }
    public String getRequirementDescription() { return requirementDescription; }
    public void setRequirementDescription(String value) { requirementDescription = value; }
    public String getApplicantName() { return applicantName; }
    public void setApplicantName(String value) { applicantName = value; }
}
