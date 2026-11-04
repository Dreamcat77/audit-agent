package com.fuquan.audit.security.domain;

/**
 * 长期登录安全审计事件类型。
 */
public enum LoginAuditEventType {
    LOGIN_SUCCESS,
    LOGIN_FAILED,
    ACCOUNT_LOCKED,
    LOGOUT
}
