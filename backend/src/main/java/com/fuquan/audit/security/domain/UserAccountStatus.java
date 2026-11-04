package com.fuquan.audit.security.domain;

/**
 * 系统账号认证状态：可用、锁定或禁用。
 */
public enum UserAccountStatus {
    ACTIVE,
    LOCKED,
    DISABLED
}
