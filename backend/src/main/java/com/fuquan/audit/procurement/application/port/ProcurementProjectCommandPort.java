package com.fuquan.audit.procurement.application.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 采购项目写入 Port，不向应用层暴露数据库实现类型。 */
public interface ProcurementProjectCommandPort {

    UUID create(CreateCommand command);

    default void update(UUID projectId, UpdateCommand command) {
        throw new UnsupportedOperationException("procurement project update is not supported");
    }

    default void delete(UUID projectId) {
        throw new UnsupportedOperationException("procurement project deletion is not supported");
    }

    void updateStatus(UUID projectId, String status);

    record CreateCommand(UUID sourceProjectId, List<UUID> sourceMaterialIds,
                         String projectCode, String projectName, String departmentName,
                         String procurementType, BigDecimal budgetAmount, String subjectCode,
                         String subjectName, String procurementMethod,
                         String requirementDescription, String applicantName,
                         String attachmentName) {
        public CreateCommand {
            sourceMaterialIds = sourceMaterialIds == null ? List.of() : List.copyOf(sourceMaterialIds);
        }

        /** Compatibility constructor for callers that still submit one primary material. */
        public CreateCommand(UUID sourceProjectId, String projectCode, String projectName, String departmentName,
                             String procurementType, BigDecimal budgetAmount, String subjectCode,
                             String subjectName, String procurementMethod,
                             String requirementDescription, String applicantName,
                             String attachmentName) {
            this(sourceProjectId, sourceProjectId == null ? List.of() : List.of(sourceProjectId),
                    projectCode, projectName, departmentName, procurementType, budgetAmount,
                    subjectCode, subjectName, procurementMethod, requirementDescription,
                    applicantName, attachmentName);
        }
    }

    record UpdateCommand(String projectName, String departmentName,
                         String procurementType, BigDecimal budgetAmount, String subjectCode,
                         String subjectName, String procurementMethod,
                         String requirementDescription, String applicantName,
                         String attachmentName) {}
}
