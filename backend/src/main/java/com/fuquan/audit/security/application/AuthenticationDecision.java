package com.fuquan.audit.security.application;

import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.security.domain.LoginFailureReason;

/**
 * 账号认证服务返回的内部决策。
 *
 * @param user 认证成功后的 Session 用户；失败时为空
 * @param failureReason 内部失败原因；成功时为空
 */
public record AuthenticationDecision(CurrentUser user, LoginFailureReason failureReason) {

    /**
     * 创建只包含 CurrentUser 的成功决策。
     *
     * @param user 已认证用户
     * @return 成功认证决策
     */
    public static AuthenticationDecision success(CurrentUser user) {
        return new AuthenticationDecision(user, null);
    }

    /**
     * 创建不包含用户身份的失败决策。
     *
     * @param failureReason 仅供服务端审计和控制流使用的失败原因
     * @return 失败认证决策
     */
    public static AuthenticationDecision failure(LoginFailureReason failureReason) {
        return new AuthenticationDecision(null, failureReason);
    }

    /**
     * 判断决策是否包含成功认证用户。
     *
     * @return 用户存在时为 {@code true}
     */
    public boolean authenticated() {
        return user != null;
    }
}
