package com.fuquan.audit.security.application.port;

import com.fuquan.audit.security.domain.LoginAuditEventType;
import com.fuquan.audit.security.domain.LoginAuditResult;
import com.fuquan.audit.security.domain.LoginFailureReason;
import java.time.Instant;
import java.util.Objects;

/**
 * 需要长期持久化的登录安全审计事实。
 *
 * @param userId 已知用户标识，未知用户名失败时可为空
 * @param username 已规范化用户名
 * @param eventType 登录成功、失败、锁定或退出事件
 * @param result 审计结果
 * @param failureReason 失败原因，成功事件必须为空
 * @param requestId 事件所属请求标识
 * @param traceId 事件所属调用链标识
 * @param sourceIp 事件来源 IP
 * @param occurredAt 事件发生时间
 */
public record LoginAuditEvent(
        String userId,
        String username,
        LoginAuditEventType eventType,
        LoginAuditResult result,
        LoginFailureReason failureReason,
        String requestId,
        String traceId,
        String sourceIp,
        Instant occurredAt) {

    /**
     * 校验审计事实必填字段及成功事件与失败原因的一致性。
     *
     * @throws NullPointerException 用户名、事件类型、结果或发生时间为空时抛出
     * @throws IllegalArgumentException 成功事件携带失败原因时抛出
     */
    public LoginAuditEvent {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (result == LoginAuditResult.SUCCESS && failureReason != null) {
            throw new IllegalArgumentException("successful login audit must not have a failureReason");
        }
    }
}
