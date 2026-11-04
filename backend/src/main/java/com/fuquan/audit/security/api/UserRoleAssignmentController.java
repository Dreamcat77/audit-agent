package com.fuquan.audit.security.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.application.UserRoleAssignmentService;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.common.application.OperationAuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 系统管理员维护用户与业务角色授权关系的 REST API。 */
@RestController
@RequestMapping("/api/v1/system/users/{userId}/roles")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class UserRoleAssignmentController {

    private final UserRoleAssignmentService service;
    private final AuthContext authContext;
    private final OperationAuditService operationAudit;

    public UserRoleAssignmentController(UserRoleAssignmentService service, AuthContext authContext,
                                        OperationAuditService operationAudit) {
        this.service = service;
        this.authContext = authContext;
        this.operationAudit = operationAudit;
    }

    /** 为用户分配一个角色；重复请求保持幂等。 */
    @PostMapping
    public ApiResponse<RoleAssignmentResponse> assign(
            @PathVariable String userId,
            @Valid @RequestBody RoleAssignmentRequest request) {
        CurrentUser user = currentUser();
        try {
            List<String> roles = service.assignRole(userId, request.roleCode(), user);
            return ApiResponse.success(new RoleAssignmentResponse(userId, roles));
        } catch (RuntimeException exception) {
            operationAudit.failure("ASSIGN_ROLE", user, "USER", userId, null, failureReason(exception));
            throw exception;
        }
    }

    /** 查询用户当前启用角色。 */
    @GetMapping
    public ApiResponse<RoleAssignmentResponse> list(@PathVariable String userId) {
        return ApiResponse.success(new RoleAssignmentResponse(userId,
                service.rolesOf(userId, currentUser())));
    }

    @DeleteMapping
    public ApiResponse<RoleAssignmentResponse> remove(@PathVariable String userId, @RequestParam String roleCode) {
        CurrentUser user = currentUser();
        try {
            return ApiResponse.success(new RoleAssignmentResponse(userId, service.removeRole(userId, roleCode, user)));
        } catch (RuntimeException exception) {
            operationAudit.failure("REMOVE_ROLE", user, "USER", userId, null, failureReason(exception));
            throw exception;
        }
    }

    private CurrentUser currentUser() {
        return authContext.currentUser().orElseThrow(() ->
                new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
    }

    private static String failureReason(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    public record RoleAssignmentRequest(@NotBlank(message = "角色编码不能为空") String roleCode) {}

    public record RoleAssignmentResponse(String userId, List<String> roles) {}
}
