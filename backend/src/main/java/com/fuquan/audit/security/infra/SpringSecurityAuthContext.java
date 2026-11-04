package com.fuquan.audit.security.infra;

import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 使用 Spring SecurityContext 实现应用层 AuthContext 端口。
 *
 * <p>适配器只接受 CurrentUser principal，并显式排除匿名 token，避免业务层依赖 Spring Security 类型。</p>
 */
@Component
public class SpringSecurityAuthContext implements AuthContext {

    /**
     * 从当前线程 SecurityContext 读取有效认证用户。
     *
     * @return CurrentUser；上下文为空、匿名或 Principal 类型不匹配时返回空
     */
    @Override
    public Optional<CurrentUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return authentication.getPrincipal() instanceof CurrentUser currentUser
                ? Optional.of(currentUser)
                : Optional.empty();
    }
}
