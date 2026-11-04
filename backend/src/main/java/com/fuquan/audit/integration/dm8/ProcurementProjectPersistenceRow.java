package com.fuquan.audit.integration.dm8;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** PROCUREMENT_DEMAND 查询投影。 */
public class ProcurementProjectPersistenceRow {
    private String id;
    private String projectCode;
    private String projectName;
    private String subjectCode;
    private String subjectName;
    private BigDecimal budgetAmount;
    private String supplierName;
    private String status;
    private LocalDateTime createdTime;
    private String departmentName;
    private String procurementType;
    private String procurementMethod;
    private String requirementDescription;
    private String applicantName;
    private String attachmentName;
    private String sourceProjectId;
    private String sourceMaterialIds;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectCode() { return projectCode; }
    public void setProjectCode(String projectCode) { this.projectCode = projectCode; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getSubjectCode() { return subjectCode; }
    public void setSubjectCode(String subjectCode) { this.subjectCode = subjectCode; }
    public String getSubjectName() { return subjectName; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }
    public BigDecimal getBudgetAmount() { return budgetAmount; }
    public void setBudgetAmount(BigDecimal budgetAmount) { this.budgetAmount = budgetAmount; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
    public String getDepartmentName() { return departmentName; }
    public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }
    public String getProcurementType() { return procurementType; }
    public void setProcurementType(String procurementType) { this.procurementType = procurementType; }
    public String getProcurementMethod() { return procurementMethod; }
    public void setProcurementMethod(String procurementMethod) { this.procurementMethod = procurementMethod; }
    public String getRequirementDescription() { return requirementDescription; }
    public void setRequirementDescription(String requirementDescription) { this.requirementDescription = requirementDescription; }
    public String getApplicantName() { return applicantName; }
    public void setApplicantName(String applicantName) { this.applicantName = applicantName; }
    public String getAttachmentName() { return attachmentName; }
    public void setAttachmentName(String attachmentName) { this.attachmentName = attachmentName; }
    public String getSourceProjectId() { return sourceProjectId; }
    public void setSourceProjectId(String sourceProjectId) { this.sourceProjectId = sourceProjectId; }
    public String getSourceMaterialIds() { return sourceMaterialIds; }
    public void setSourceMaterialIds(String sourceMaterialIds) { this.sourceMaterialIds = sourceMaterialIds; }
}
