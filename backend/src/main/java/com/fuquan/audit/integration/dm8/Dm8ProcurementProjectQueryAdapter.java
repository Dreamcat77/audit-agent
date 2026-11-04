package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.procurement.application.port.ProcurementProjectQueryPort;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** DM8 采购项目查询适配器，事实源为 PROCUREMENT_DEMAND。 */
@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProcurementProjectQueryAdapter implements ProcurementProjectQueryPort,
        com.fuquan.audit.procurement.application.port.ProcurementProjectCommandPort {

    private final ProcurementProjectMapper mapper;

    public Dm8ProcurementProjectQueryAdapter(ProcurementProjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Page findPage(String status, String keyword, int offset, int limit) {
        return new Page(mapper.findPage(status, keyword, offset, offset + limit).stream()
                .map(Dm8ProcurementProjectQueryAdapter::toDomain).toList(),
                mapper.count(status, keyword));
    }

    @Override
    public Optional<ProcurementProject> findById(UUID id) {
        ProcurementProjectPersistenceRow row = mapper.findById(id.toString());
        return row == null ? Optional.empty() : Optional.of(toDomain(row));
    }

    private static ProcurementProject toDomain(ProcurementProjectPersistenceRow row) {
        UUID primaryMaterialId = row.getSourceProjectId() == null ? null : UUID.fromString(row.getSourceProjectId());
        List<UUID> materialIds = row.getSourceMaterialIds() == null || row.getSourceMaterialIds().isBlank()
                ? primaryMaterialId == null ? List.of() : List.of(primaryMaterialId)
                : java.util.Arrays.stream(row.getSourceMaterialIds().split(","))
                        .map(String::trim).map(UUID::fromString).toList();
        return new ProcurementProject(UUID.fromString(row.getId()), row.getProjectCode(),
                row.getProjectName(), row.getSubjectCode(), row.getSubjectName(), row.getBudgetAmount(),
                row.getSupplierName(), row.getStatus(), row.getCreatedTime() == null
                        ? null : row.getCreatedTime().toInstant(ZoneOffset.UTC), row.getDepartmentName(),
                row.getProcurementType(), row.getProcurementMethod(), row.getRequirementDescription(),
                row.getApplicantName(), row.getAttachmentName(), primaryMaterialId, materialIds);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public UUID create(com.fuquan.audit.procurement.application.port.ProcurementProjectCommandPort.CreateCommand command) {
        List<UUID> materialIds = command.sourceMaterialIds();
        // Serialize competing creates on the durable source, including across application instances.
        for (UUID materialId : materialIds.stream().sorted().toList()) {
            if (mapper.lockParsedMaterial(materialId.toString()) == null)
                throw new BusinessException(BusinessErrorCode.CONFLICT, "所选材料尚未解析完成，请重新选择材料");
            if (mapper.countMaterialProjects(materialId.toString()) > 0)
                throw new BusinessException(BusinessErrorCode.CONFLICT, "部分材料已关联其他采购需求，请重新核对材料清单");
        }
        UUID id = UUID.randomUUID();
        int inserted = mapper.insert(id.toString(), command.sourceProjectId().toString(), command.projectCode(),
                command.projectName(), command.departmentName(),
                command.procurementType(), command.budgetAmount(), command.subjectCode(), command.subjectName(),
                command.procurementMethod(), command.requirementDescription(), command.applicantName(),
                command.attachmentName());
        if (inserted != 1 || mapper.insertLegacyMaterialLink(id.toString(), command.sourceProjectId().toString()) != 1)
            throw new BusinessException(BusinessErrorCode.CONFLICT, "采购材料关联未保存，请核对后重试");
        for (int index = 0; index < materialIds.size(); index++) {
            UUID materialId = materialIds.get(index);
            int primary = materialId.equals(command.sourceProjectId()) ? 1 : 0;
            if (mapper.insertMaterialLink(id.toString(), materialId.toString(), index + 1, primary) != 1)
                throw new BusinessException(BusinessErrorCode.CONFLICT, "采购材料清单未完整保存，请核对后重试");
        }
        return id;
    }

    @Override
    public void update(UUID projectId, com.fuquan.audit.procurement.application.port.ProcurementProjectCommandPort.UpdateCommand command) {
        int updated = mapper.update(projectId.toString(), command.projectName(), command.departmentName(),
                command.procurementType(), command.budgetAmount(), command.subjectCode(), command.subjectName(),
                command.procurementMethod(), command.requirementDescription(), command.applicantName(),
                command.attachmentName());
        if (updated != 1) {
            throw new IllegalArgumentException("procurement project update conflict: " + projectId);
        }
    }

    @Override
    public void delete(UUID projectId) {
        int deleted = mapper.softDelete(projectId.toString());
        if (deleted != 1) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "procurement project deletion conflict: " + projectId);
        }
    }

    @Override
    public void updateStatus(UUID projectId, String status) {
        int updated = mapper.updateStatus(projectId.toString(), status);
        if (updated != 1) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "采购项目状态更新失败: " + projectId);
        }
    }
}
