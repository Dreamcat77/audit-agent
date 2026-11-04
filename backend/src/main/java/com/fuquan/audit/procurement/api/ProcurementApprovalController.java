package com.fuquan.audit.procurement.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.ProcurementApprovalWorkflowService;
import com.fuquan.audit.procurement.application.port.ProcurementApprovalRepository.Workflow;
import com.fuquan.audit.procurement.domain.ProcurementApprovalInstance;
import com.fuquan.audit.procurement.domain.ProcurementApprovalNode;
import com.fuquan.audit.procurement.domain.ProcurementApprovalOperation;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.procurement.application.ProcurementProjectQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 采购需求审批流程 REST API。 */
@RestController
@RequestMapping("/api/v1/procurement/projects/{projectId}/approval")
public class ProcurementApprovalController {

    private final ProcurementApprovalWorkflowService workflowService;
    private final AuthContext authContext;
    private final OperationAuditService operationAudit;
    private final ProcurementProjectQueryService projects;

    public ProcurementApprovalController(ProcurementApprovalWorkflowService workflowService,
                                          AuthContext authContext, OperationAuditService operationAudit,
                                          ProcurementProjectQueryService projects) {
        this.workflowService = workflowService;
        this.authContext = authContext;
        this.operationAudit = operationAudit;
        this.projects = projects;
    }

    @PostMapping("/start")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_APPLICANT')")
    public ApiResponse<ApprovalResponse> start(@PathVariable String projectId,
                                               @RequestBody(required = false) StartRequest request) {
        CurrentUser user=currentUser();
        try { UUID id=parse(projectId); var result=workflowService.start(id,user,
                    request == null ? null : request.modificationNote());
            operationAudit.success("START_APPROVAL",user,"APPROVAL",result.instance().id().toString(),
                    projects.requireById(id).projectName(),null,result.instance(),"发起采购审批");
            return ApiResponse.success(ApprovalResponse.from(result));
        } catch (RuntimeException exception) {
            operationAudit.failure("START_APPROVAL", user, "APPROVAL", projectId, null, failureReason(exception));
            throw exception;
        }
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_APPLICANT','PROCUREMENT_REVIEWER','FINANCE_REVIEWER','PROCUREMENT_LEADER','PROCUREMENT_COMMITTEE','AUDITOR')")
    public ApiResponse<ApprovalResponse> get(@PathVariable String projectId) {
        return ApiResponse.success(ApprovalResponse.from(workflowService.require(parse(projectId))));
    }

    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_MANAGER','PROCUREMENT_APPLICANT','PROCUREMENT_REVIEWER','FINANCE_REVIEWER','PROCUREMENT_LEADER','PROCUREMENT_COMMITTEE','AUDITOR')")
    public ApiResponse<List<ApprovalOperationResponse>> history(@PathVariable String projectId) {
        return ApiResponse.success(workflowService.history(parse(projectId)).stream()
                .map(ApprovalOperationResponse::from).toList());
    }

    @PostMapping("/approve")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','AUDITOR','PROCUREMENT_REVIEWER','FINANCE_REVIEWER','PROCUREMENT_LEADER','PROCUREMENT_COMMITTEE')")
    public ApiResponse<ApprovalResponse> approve(@PathVariable String projectId,
                                                 @Valid @RequestBody(required = false) ApprovalOpinionRequest request) {
        return audited(projectId,"APPROVE","审批通过", request == null ? null : request.opinion());
    }

    @PostMapping("/return")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','AUDITOR','PROCUREMENT_REVIEWER','FINANCE_REVIEWER','PROCUREMENT_LEADER','PROCUREMENT_COMMITTEE')")
    public ApiResponse<ApprovalResponse> returnForModification(
            @PathVariable String projectId, @Valid @RequestBody ApprovalOpinionRequest request) {
        return audited(projectId,"RETURN","退回修改",request.opinion());
    }

    @PostMapping("/comments")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','AUDITOR','PROCUREMENT_REVIEWER','FINANCE_REVIEWER','PROCUREMENT_LEADER','PROCUREMENT_COMMITTEE')")
    public ApiResponse<ApprovalResponse> comment(
            @PathVariable String projectId, @Valid @RequestBody ApprovalOpinionRequest request) {
        return audited(projectId,"COMMENT","添加审批意见",request.opinion());
    }

    @PostMapping("/reassign")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ApiResponse<ApprovalResponse> reassign(@PathVariable String projectId,
                                                  @Valid @RequestBody ReassignRequest request) {
        CurrentUser user=currentUser();
        try { UUID id=parse(projectId); var result=workflowService.reassign(id,
                    request.nodeCode(),request.targetUserId(),request.opinion(),user);
            operationAudit.success("REASSIGN_APPROVAL",user,"APPROVAL",result.instance().id().toString(),
                    projects.requireById(id).projectName(),null,result.instance(),"改派审批人");
            return ApiResponse.success(ApprovalResponse.from(result));
        } catch (RuntimeException exception) {
            operationAudit.failure("REASSIGN_APPROVAL", user, "APPROVAL", projectId, null, failureReason(exception));
            throw exception;
        }
    }

    private ApiResponse<ApprovalResponse> audited(String projectId,String action,String description,String opinion) {
        CurrentUser user=currentUser();
        try { UUID id=parse(projectId); Workflow result = switch(action) {
                case "APPROVE" -> workflowService.approve(id,user,opinion);
                case "RETURN" -> workflowService.returnForModification(id,user,opinion);
                default -> workflowService.addComment(id,user,opinion); };
            operationAudit.success(action+"_APPROVAL",user,"APPROVAL",result.instance().id().toString(),
                    projects.requireById(id).projectName(),null,result.instance(),description);
            return ApiResponse.success(ApprovalResponse.from(result));
        } catch (RuntimeException exception) {
            operationAudit.failure(action+"_APPROVAL", user, "APPROVAL", projectId, null, failureReason(exception));
            throw exception;
        }
    }

    private static String failureReason(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private CurrentUser currentUser() {
        return authContext.currentUser().orElseThrow(() ->
                new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
    }

    private static UUID parse(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,
                    "projectId must be a valid UUID", exception);
        }
    }

    public record ApprovalOpinionRequest(@NotBlank(message = "审批意见不能为空") String opinion) {}

    public record StartRequest(String modificationNote) {}

    public record ReassignRequest(@NotBlank(message = "节点编码不能为空") String nodeCode,
                                  @NotBlank(message = "目标审批人不能为空") String targetUserId,
                                  String opinion) {}

    public record ApprovalResponse(UUID id, UUID projectId, String status, String currentNodeCode,
                                   String startedByUserId, String startedByName, Instant startedTime,
                                   Instant completedTime, Instant createdTime, Instant updatedTime,
                                   List<ApprovalNodeResponse> nodes) {
        static ApprovalResponse from(Workflow workflow) {
            ProcurementApprovalInstance instance = workflow.instance();
            return new ApprovalResponse(instance.id(), instance.projectId(), instance.status(),
                    instance.currentNodeCode(), instance.startedByUserId(), instance.startedByName(),
                    instance.startedTime(), instance.completedTime(), instance.createdTime(), instance.updatedTime(),
                    workflow.nodes().stream().map(ApprovalNodeResponse::from).toList());
        }
    }

    public record ApprovalNodeResponse(UUID id, UUID instanceId, String nodeCode, String nodeName,
                                       int nodeOrder, String status, String requiredRoleCode, String assigneeUserId,
                                       String assigneeName, Instant startedTime, Instant completedTime,
                                       String assignmentMessage) {
        static ApprovalNodeResponse from(ProcurementApprovalNode node) {
            return new ApprovalNodeResponse(node.id(), node.instanceId(), node.nodeCode(), node.nodeName(),
                    node.nodeOrder(), node.status(), node.requiredRoleCode(), node.assigneeUserId(), node.assigneeName(),
                    node.startedTime(), node.completedTime(), assignmentMessage(node));
        }

        private static String assignmentMessage(ProcurementApprovalNode node) {
            return node.assigneeUserId() == null && !"COMPLETED".equals(node.status())
                    ? "该角色暂无可用审批人，请管理员改派" : null;
        }
    }

    public record ApprovalOperationResponse(UUID id, UUID instanceId, UUID projectId, String nodeCode,
                                            String operationType, String operatorUserId, String operatorName,
                                            String opinion, String fromStatus, String toStatus,
                                            Instant operatedTime, Instant createdTime,
                                            String originalAssigneeUserId, String originalAssigneeName,
                                            String newAssigneeUserId, String newAssigneeName, boolean adminActing) {
        static ApprovalOperationResponse from(ProcurementApprovalOperation operation) {
            return new ApprovalOperationResponse(operation.id(), operation.instanceId(), operation.projectId(),
                    operation.nodeCode(), operation.operationType(), operation.operatorUserId(),
                    operation.operatorName(), operation.opinion(), operation.fromStatus(), operation.toStatus(),
                    operation.operatedTime(), operation.createdTime(), operation.originalAssigneeUserId(),
                    operation.originalAssigneeName(), operation.newAssigneeUserId(), operation.newAssigneeName(),
                    operation.adminActing());
        }
    }
}
