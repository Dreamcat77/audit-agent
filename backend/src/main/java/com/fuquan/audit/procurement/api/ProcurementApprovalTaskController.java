package com.fuquan.audit.procurement.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.ProcurementApprovalWorkflowService;
import com.fuquan.audit.procurement.application.port.ProcurementApprovalTask;
import com.fuquan.audit.procurement.application.port.ProcurementNotificationPort;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前登录用户的采购审批待办查询。 */
@RestController
@RequestMapping("/api/v1/procurement/approval")
public class ProcurementApprovalTaskController {
    private final ProcurementApprovalWorkflowService service;
    private final AuthContext authContext;
    private final ProcurementNotificationPort notifications;

    public ProcurementApprovalTaskController(ProcurementApprovalWorkflowService service, AuthContext authContext,
                                             ProcurementNotificationPort notifications) {
        this.service = service;
        this.authContext = authContext;
        this.notifications = notifications;
    }

    @GetMapping("/tasks")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','PROCUREMENT_APPLICANT','PROCUREMENT_MANAGER','PROCUREMENT_REVIEWER','FINANCE_REVIEWER','PROCUREMENT_LEADER','PROCUREMENT_COMMITTEE','AUDITOR')")
    public ApiResponse<List<TaskResponse>> tasks() {
        CurrentUser user = authContext.currentUser().orElseThrow(() ->
                new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
        List<TaskResponse> result = new java.util.ArrayList<>(service.pendingTasks(user.userId()).stream()
                .map(TaskResponse::from).toList());
        notifications.findByRecipient(user.userId(), false).stream()
                .filter(item -> "PENDING_MODIFICATION".equals(item.type()))
                .filter(item -> isStillReturned(item.projectId()))
                .map(TaskResponse::from).forEach(result::add);
        return ApiResponse.success(result);
    }

    private boolean isStillReturned(java.util.UUID projectId) {
        try {
            return "RETURNED".equals(service.require(projectId).instance().status());
        } catch (BusinessException exception) {
            return false;
        }
    }

    public record TaskResponse(String taskType, String projectId, String projectCode, String projectName,
                               java.math.BigDecimal budgetAmount, String instanceId,
                               String instanceStatus, String nodeId, String nodeCode, String nodeName,
                               String requiredRoleCode, String assigneeUserId, String assigneeName,
                               Instant startedTime, String returnOperatorName, String returnOpinion) {
        static TaskResponse from(ProcurementApprovalTask task) {
            return new TaskResponse("APPROVAL", task.projectId().toString(), task.projectCode(), task.projectName(),
                    task.budgetAmount(), task.instanceId().toString(), task.instanceStatus(), task.nodeId().toString(),
                    task.nodeCode(), task.nodeName(), task.requiredRoleCode(), task.assigneeUserId(),
                    task.assigneeName(), task.startedTime(), null, null);
        }
        static TaskResponse from(com.fuquan.audit.procurement.domain.ProcurementNotification item) {
            return new TaskResponse("PENDING_MODIFICATION", item.projectId().toString(), null, item.projectName(),
                    null, null, "RETURNED", item.id().toString(), "PENDING_MODIFICATION", "待修改",
                    "PROCUREMENT_APPLICANT", item.recipientUserId(), null, item.createdTime(),
                    item.operatorName(), item.opinion());
        }
    }
}
