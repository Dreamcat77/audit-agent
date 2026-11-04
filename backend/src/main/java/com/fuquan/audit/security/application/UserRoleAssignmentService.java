package com.fuquan.audit.security.application;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.OperationAuditResultStatus;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.security.application.port.UserAccountRoleQueryPort;
import com.fuquan.audit.security.application.port.UserRoleAssignmentPort;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.security.domain.UserAccount;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

/** 系统用户角色授权用例。只有系统管理员可以修改授权关系。 */
@Service
public class UserRoleAssignmentService {

    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";

    private final UserAccountRoleQueryPort userQuery;
    private final UserRoleAssignmentPort assignmentPort;
    private final OperationAuditPort operationAuditPort;
    private final OperationAuditService operationAuditService;

    public UserRoleAssignmentService(
            UserAccountRoleQueryPort userQuery,
            UserRoleAssignmentPort assignmentPort,
            OperationAuditPort operationAuditPort) {
        this(userQuery, assignmentPort, operationAuditPort, null);
    }

    @Autowired
    public UserRoleAssignmentService(
            UserAccountRoleQueryPort userQuery,
            UserRoleAssignmentPort assignmentPort,
            OperationAuditPort operationAuditPort,
            OperationAuditService operationAuditService) {
        this.userQuery = userQuery;
        this.assignmentPort = assignmentPort;
        this.operationAuditPort = operationAuditPort;
        this.operationAuditService = operationAuditService;
    }

    /**
     * 为启用用户分配启用角色；重复分配视为成功但不重复写关系。
     *
     * @param userId 目标用户标识
     * @param requestedRoleCode 角色编码
     * @param operator 当前操作人
     * @return 目标用户的最新角色编码
     */
    @Transactional
    public List<String> assignRole(String userId, String requestedRoleCode, CurrentUser operator) {
        requireAdmin(operator);
        String normalizedUserId = requireText(userId, "用户ID不能为空");
        String roleCode = normalizeRoleCode(requestedRoleCode);
        UserAccount target = userQuery.findActiveById(normalizedUserId)
                .orElseThrow(() -> new BusinessException(
                        BusinessErrorCode.NOT_FOUND, "目标用户不存在或已停用"));
        if (!assignmentPort.isActiveRole(roleCode)) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "角色不存在或已停用: " + roleCode);
        }
        boolean alreadyAssigned = assignmentPort.hasRole(target.id(), roleCode);
        List<String> before = assignmentPort.findActiveRoleCodesByUserId(target.id());
        if (!alreadyAssigned) {
            assignmentPort.assignRole(target.id(), roleCode);
        }
        List<String> after = assignmentPort.findActiveRoleCodesByUserId(target.id());
        if (operationAuditService != null) {
            operationAuditService.success("ASSIGN_ROLE", operator, "USER", target.id(), target.realName(),
                    before, after, alreadyAssigned ? "角色已存在" : "分配用户角色 " + roleCode);
        } else operationAuditPort.append(new OperationAuditEvent(
                "AUTHORIZATION",
                "SECURITY",
                "ASSIGN_ROLE",
                operator.userId(),
                operator.username(),
                "SYS_USER",
                target.id(),
                null,
                OperationAuditResultStatus.SUCCESS,
                alreadyAssigned ? "ROLE_ALREADY_ASSIGNED" : "ROLE_ASSIGNED",
                "为系统用户分配业务角色",
                "roleCode=" + roleCode + ", targetUsername=" + target.username(),
                null,
                null,
                null,
                Instant.now()));
        return after;
    }

    /** 查询用户的启用角色，供管理员核对授权结果。 */
    @Transactional(readOnly = true)
    public List<String> rolesOf(String userId, CurrentUser operator) {
        requireAdmin(operator);
        String normalizedUserId = requireText(userId, "用户ID不能为空");
        userQuery.findActiveById(normalizedUserId).orElseThrow(() -> new BusinessException(
                BusinessErrorCode.NOT_FOUND, "目标用户不存在或已停用"));
        return assignmentPort.findActiveRoleCodesByUserId(normalizedUserId);
    }

    @Transactional
    public List<String> removeRole(String userId, String requestedRoleCode, CurrentUser operator) {
        requireAdmin(operator); String normalizedUserId=requireText(userId,"用户ID不能为空");
        String roleCode=normalizeRoleCode(requestedRoleCode);
        UserAccount target=userQuery.findActiveById(normalizedUserId).orElseThrow(() ->
                new BusinessException(BusinessErrorCode.NOT_FOUND,"目标用户不存在或已停用"));
        List<String> before=assignmentPort.findActiveRoleCodesByUserId(target.id());
        assignmentPort.removeRole(target.id(),roleCode);
        List<String> after=assignmentPort.findActiveRoleCodesByUserId(target.id());
        if (operationAuditService != null) {
            operationAuditService.success("REMOVE_ROLE", operator, "USER", target.id(), target.realName(),
                    before, after, "移除用户角色 " + roleCode);
        } else operationAuditPort.append(new OperationAuditEvent("AUTHORIZATION","SECURITY","REMOVE_ROLE",
                operator.userId(),operator.username(),"USER",target.id(),null,OperationAuditResultStatus.SUCCESS,
                "ROLE_REMOVED","移除系统用户业务角色","roleCode="+roleCode,null,null,null,Instant.now(),
                target.realName(), before.toString(), after.toString(), null,
                null, null, null, null, null, null));
        return after;
    }

    private static void requireAdmin(CurrentUser operator) {
        if (operator == null || !operator.roles().contains(SYSTEM_ADMIN)) {
            throw new BusinessException(BusinessErrorCode.FORBIDDEN, "只有系统管理员可以分配用户角色");
        }
    }

    private static String normalizeRoleCode(String value) {
        String roleCode = requireText(value, "角色编码不能为空").toUpperCase(Locale.ROOT);
        if (!roleCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "角色编码格式不正确");
        }
        return roleCode;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, message);
        }
        return value.trim();
    }
}
