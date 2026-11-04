package com.fuquan.audit.procurement.infra.memory;

import com.fuquan.audit.budget.infra.memory.LocalBudgetFixtures;
import com.fuquan.audit.procurement.application.port.ProcurementProjectQueryPort;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** local profile 的采购项目查询适配器。 */
@Repository
@Profile("local")
public class InMemoryProcurementProjectQueryAdapter implements ProcurementProjectQueryPort,
        com.fuquan.audit.procurement.application.port.ProcurementProjectCommandPort {

    private final List<ProcurementProject> projects = new ArrayList<>(LocalBudgetFixtures.procurementDemands().stream()
            .map(item -> new ProcurementProject(item.id(), item.budgetApprovalId(), item.procurementName(),
                    item.subjectCode(), item.subjectName(), item.amount(), item.supplierName(),
                    item.status(), item.createdTime(), "信息中心", "货物", "公开招标",
                    "采购办公设备", "测试申请人", null))
            .sorted(Comparator.comparing(ProcurementProject::createdTime,
                    Comparator.nullsLast(Comparator.reverseOrder())))
            .toList());
    private final java.util.Map<UUID, List<UUID>> materialLinks = new java.util.HashMap<>();

    @Override
    public synchronized Page findPage(String status, String keyword, int offset, int limit) {
        List<ProcurementProject> filtered = projects.stream()
                .filter(item -> status == null || status.isBlank() || status.equalsIgnoreCase(item.status()))
                .filter(item -> keyword == null || keyword.isBlank()
                        || contains(item.projectName(), keyword))
                .toList();
        return new Page(filtered.stream().skip(offset).limit(limit).toList(), filtered.size());
    }

    @Override
    public synchronized Optional<ProcurementProject> findById(UUID id) {
        return projects.stream().filter(item -> item.id().equals(id)).findFirst();
    }

    @Override
    public synchronized UUID create(com.fuquan.audit.procurement.application.port.ProcurementProjectCommandPort.CreateCommand command) {
        Set<UUID> used = usedMaterialIds();
        if (command.sourceMaterialIds().stream().anyMatch(used::contains))
            throw new com.fuquan.audit.common.exception.BusinessException(
                    com.fuquan.audit.common.exception.BusinessErrorCode.CONFLICT,
                    "部分材料已关联其他采购需求，请重新核对材料清单");
        UUID id = UUID.randomUUID();
        projects.add(0, new ProcurementProject(id, command.projectCode(), command.projectName(),
                command.subjectCode(), command.subjectName(), command.budgetAmount(), null, "DRAFT",
                java.time.Instant.now(), command.departmentName(), command.procurementType(),
                command.procurementMethod(), command.requirementDescription(), command.applicantName(),
                command.attachmentName(), command.sourceProjectId(), command.sourceMaterialIds()));
        materialLinks.put(id, List.copyOf(command.sourceMaterialIds()));
        return id;
    }

    public synchronized Set<UUID> usedMaterialIds() {
        Set<UUID> used = new java.util.HashSet<>(materialLinks.values().stream().flatMap(List::stream).toList());
        projects.stream().map(ProcurementProject::sourceProjectId).filter(java.util.Objects::nonNull).forEach(used::add);
        return Set.copyOf(used);
    }

    @Override
    public synchronized void update(UUID projectId, com.fuquan.audit.procurement.application.port.ProcurementProjectCommandPort.UpdateCommand command) {
        for (int i = 0; i < projects.size(); i++) {
            ProcurementProject project = projects.get(i);
            if (project.id().equals(projectId)) {
                projects.set(i, new ProcurementProject(project.id(), project.projectCode(), command.projectName(),
                        command.subjectCode(), command.subjectName(), command.budgetAmount(), project.supplierName(),
                        project.status(), project.createdTime(), command.departmentName(), command.procurementType(),
                        command.procurementMethod(), command.requirementDescription(), command.applicantName(),
                        command.attachmentName() == null ? project.attachmentName() : command.attachmentName(),
                        project.sourceProjectId(), project.sourceMaterialIds()));
                return;
            }
        }
        throw new IllegalArgumentException("procurement project not found: " + projectId);
    }

    @Override
    public synchronized void delete(UUID projectId) {
        boolean removed = projects.removeIf(project -> project.id().equals(projectId));
        if (!removed) {
            throw new IllegalArgumentException("procurement project not found: " + projectId);
        }
        materialLinks.remove(projectId);
    }

    @Override
    public synchronized void updateStatus(UUID projectId, String status) {
        for (int i = 0; i < projects.size(); i++) {
            ProcurementProject project = projects.get(i);
            if (project.id().equals(projectId)) {
                projects.set(i, new ProcurementProject(project.id(), project.projectCode(), project.projectName(),
                        project.subjectCode(), project.subjectName(), project.budgetAmount(), project.supplierName(),
                        status, project.createdTime(), project.departmentName(), project.procurementType(),
                        project.procurementMethod(), project.requirementDescription(), project.applicantName(),
                        project.attachmentName(), project.sourceProjectId(), project.sourceMaterialIds()));
                return;
            }
        }
        throw new IllegalArgumentException("procurement project not found: " + projectId);
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword.toLowerCase());
    }
}
