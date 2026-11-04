package com.fuquan.audit.procurement.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 采购项目查询投影，事实来源为 PROCUREMENT_DEMAND。 */
public record ProcurementProject(
        UUID id,
        String projectCode,
        String projectName,
        String subjectCode,
        String subjectName,
        BigDecimal budgetAmount,
        String supplierName,
        String status,
        Instant createdTime,
        String departmentName,
        String procurementType,
        String procurementMethod,
        String requirementDescription,
        String applicantName,
        String attachmentName,
        UUID sourceProjectId,
        List<UUID> sourceMaterialIds) {

    public ProcurementProject(UUID id, String projectCode, String projectName,
                              String subjectCode, String subjectName, BigDecimal budgetAmount,
                              String supplierName, String status, Instant createdTime,
                              String departmentName, String procurementType, String procurementMethod,
                              String requirementDescription, String applicantName, String attachmentName,
                              UUID sourceProjectId) {
        this(id, projectCode, projectName, subjectCode, subjectName, budgetAmount, supplierName,
                status, createdTime, departmentName, procurementType, procurementMethod,
                requirementDescription, applicantName, attachmentName, sourceProjectId,
                sourceProjectId == null ? List.of() : List.of(sourceProjectId));
    }

    public ProcurementProject(UUID id, String projectCode, String projectName,
                              String subjectCode, String subjectName, BigDecimal budgetAmount,
                              String supplierName, String status, Instant createdTime,
                              String departmentName, String procurementType, String procurementMethod,
                              String requirementDescription, String applicantName, String attachmentName) {
        this(id, projectCode, projectName, subjectCode, subjectName, budgetAmount, supplierName,
                status, createdTime, departmentName, procurementType, procurementMethod,
                requirementDescription, applicantName, attachmentName, null);
    }

    public ProcurementProject(UUID id, String projectCode, String projectName,
                              String subjectCode, String subjectName, BigDecimal budgetAmount,
                              String supplierName, String status, Instant createdTime) {
        this(id, projectCode, projectName, subjectCode, subjectName, budgetAmount, supplierName,
                status, createdTime, null, null, null, null, null, null, null);
    }

    public ProcurementProject {
        Objects.requireNonNull(id, "id must not be null");
        sourceMaterialIds = sourceMaterialIds == null ? List.of() : List.copyOf(sourceMaterialIds);
        if (projectName == null || projectName.isBlank()) {
            throw new IllegalArgumentException("projectName must not be blank");
        }
    }
}
