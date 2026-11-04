package com.fuquan.audit.security.domain;

/**
 * 服务端内部记录的认证失败原因。
 *
 * <p>对外登录接口使用统一未认证响应，不以该枚举向客户端泄露账号存在性或状态。</p>
 */
public enum LoginFailureReason {
    BAD_CREDENTIALS,
    DISABLED,
    LOCKED
}
