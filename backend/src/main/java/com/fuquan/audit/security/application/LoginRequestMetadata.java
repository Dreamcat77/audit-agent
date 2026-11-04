package com.fuquan.audit.security.application;

/**
 * 当前认证或退出请求级的审计元数据。
 *
 * <p>该对象只在本次认证调用和安全审计写入期间存活，不是 CurrentUser 的组成部分，也不得进入长期 SecurityContext/Session 状态。</p>
 *
 * @param requestId 当前 HTTP 请求标识
 * @param traceId 当前调用链标识
 * @param sourceIp 当前请求来源 IP
 */
public record LoginRequestMetadata(String requestId, String traceId, String sourceIp) {

    /**
     * 创建字段均为空的安全默认元数据，供缺少请求上下文的内部调用使用。
     *
     * @return 空请求元数据
     */
    public static LoginRequestMetadata empty() {
        return new LoginRequestMetadata(null, null, null);
    }
}
