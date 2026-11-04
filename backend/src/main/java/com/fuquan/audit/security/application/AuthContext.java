package com.fuquan.audit.security.application;

import com.fuquan.audit.security.domain.CurrentUser;
import java.util.Optional;

/**
 * 应用层读取当前认证用户的安全端口。
 *
 * <p>业务模块只依赖该抽象，不直接依赖 Spring Security 的 Authentication 或 Session 实现。</p>
 */
public interface AuthContext {

    /**
     * 读取当前请求关联的认证用户。
     *
     * @return 已认证 CurrentUser；匿名或无有效 Session 时返回空
     */
    Optional<CurrentUser> currentUser();
}
