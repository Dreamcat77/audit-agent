package com.fuquan.audit.security.application.port;

/**
 * 登录安全事实的只追加持久化端口。
 *
 * <p>正式环境必须将登录、锁定和退出审计持久化到 DM8；Redis Session 不是长期安全审计事实源。</p>
 */
public interface LoginAuditPort {

    /**
     * 追加一条不可覆盖的认证安全审计事实。
     *
     * @param event 待持久化登录审计事件
     */
    void append(LoginAuditEvent event);
}
