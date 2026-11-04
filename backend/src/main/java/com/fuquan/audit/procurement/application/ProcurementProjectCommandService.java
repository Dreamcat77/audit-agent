package com.fuquan.audit.procurement.application;

import com.fuquan.audit.procurement.application.port.ProcurementProjectCommandPort;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.security.domain.CurrentUser;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 新建采购需求用例。 */
@Service
public class ProcurementProjectCommandService {

    private final ProcurementProjectCommandPort commandPort;
    private final ProcurementApprovalWorkflowService approvalWorkflowService;
    private final ProcurementProjectQueryService projectQueryService;
    private final ProcurementAgentReviewSnapshotService agentReviewSnapshotService;
    private final ProcurementSourceProjectService sourceProjectService;

    public ProcurementProjectCommandService(ProcurementProjectCommandPort commandPort) {
        this(commandPort, null, null, null, null);
    }

    @Autowired
    public ProcurementProjectCommandService(ProcurementProjectCommandPort commandPort,
                                            ProcurementApprovalWorkflowService approvalWorkflowService,
                                            ProcurementProjectQueryService projectQueryService,
                                            ProcurementAgentReviewSnapshotService agentReviewSnapshotService,
                                            ProcurementSourceProjectService sourceProjectService) {
        this.commandPort = commandPort;
        this.approvalWorkflowService = approvalWorkflowService;
        this.projectQueryService = projectQueryService;
        this.agentReviewSnapshotService = agentReviewSnapshotService;
        this.sourceProjectService = sourceProjectService;
    }

    @Transactional
    public UUID create(ProcurementProjectCommandPort.CreateCommand command) {
        return commandPort.create(validated(command));
    }

    /** 创建并提交采购需求，提交成功后在同一业务事务中初始化审批流程。 */
    @Transactional
    public UUID create(ProcurementProjectCommandPort.CreateCommand command, CurrentUser operator) {
        UUID projectId = commandPort.create(validated(command));
        if (approvalWorkflowService == null) {
            throw new IllegalStateException("approval workflow service is required for submitted projects");
        }
        approvalWorkflowService.start(projectId, operator);
        return projectId;
    }

    private ProcurementProjectCommandPort.CreateCommand validated(
            ProcurementProjectCommandPort.CreateCommand command) {
        if (sourceProjectService == null) {
            throw new IllegalStateException("source project service is required for procurement project creation");
        }
        if (command.sourceProjectId() == null) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED, "请选择已完成解析的项目");
        }
        List<UUID> materialIds = command.sourceMaterialIds().isEmpty()
                ? List.of(command.sourceProjectId()) : List.copyOf(command.sourceMaterialIds());
        if (materialIds.size() > 100 || new LinkedHashSet<>(materialIds).size() != materialIds.size()
                || materialIds.stream().anyMatch(java.util.Objects::isNull)
                || !materialIds.contains(command.sourceProjectId())) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "请提交 1 至 100 份采购材料，并确保材料清单完整");
        }
        var sources = materialIds.stream().map(sourceProjectService::requireEligible).toList();
        var source = sources.stream().filter(item -> item.id().equals(command.sourceProjectId()))
                .findFirst().orElseThrow();
        String name = command.projectName() == null || command.projectName().isBlank() ? source.name() : command.projectName().trim();
        if (name == null || name.isBlank() || name.length() > 200)
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED, "请确认项目名称");
        return new ProcurementProjectCommandPort.CreateCommand(source.id(), materialIds, command.projectCode(), name,
                command.departmentName(), command.procurementType(), command.budgetAmount(), command.subjectCode(),
                command.subjectName(), command.procurementMethod(), command.requirementDescription(),
                command.applicantName(), command.attachmentName());
    }

    /** 仅允许修改尚未发起审批的草稿项目。 */
    @Transactional
    public ProcurementProject update(UUID projectId, ProcurementProjectCommandPort.UpdateCommand command) {
        if (projectQueryService == null) {
            throw new IllegalStateException("project query service is required for project updates");
        }
        var project = projectQueryService.requireById(projectId);
        if (!"DRAFT".equalsIgnoreCase(project.status())) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "只有草稿状态的采购项目可以编辑，当前状态: " + project.status());
        }
        commandPort.update(projectId, command);
        if (agentReviewSnapshotService != null) {
            agentReviewSnapshotService.delete(projectId);
        }
        return projectQueryService.requireById(projectId);
    }

    /** 逻辑删除采购项目，保留审批、报告和询比价历史记录。 */
    @Transactional
    public void delete(UUID projectId) {
        if (projectQueryService == null) {
            throw new IllegalStateException("project query service is required for project deletion");
        }
        projectQueryService.requireById(projectId);
        commandPort.delete(projectId);
        if (agentReviewSnapshotService != null) {
            agentReviewSnapshotService.delete(projectId);
        }
    }
}
