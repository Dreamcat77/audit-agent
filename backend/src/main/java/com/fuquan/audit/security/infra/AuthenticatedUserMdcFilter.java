package com.fuquan.audit.security.infra;

import com.fuquan.audit.common.web.RequestAuditLoggingFilter;
import com.fuquan.audit.security.domain.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 将当前认证用户标识暴露给请求审计日志和 MDC。
 *
 * <p>过滤器只处理非敏感 userId，并在请求结束时清理线程 MDC，避免线程复用造成身份串扰。</p>
 */
public class AuthenticatedUserMdcFilter extends OncePerRequestFilter {

    public static final String USER_ID_MDC_KEY = "userId";

    /**
     * 在过滤器链执行期间绑定用户 MDC，并在 finally 中补充请求属性和清理 MDC。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param filterChain 后续 Servlet 过滤器链
     * @throws ServletException 下游 Servlet 处理失败时抛出
     * @throws IOException 下游读写失败时抛出
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        CurrentUser currentUser = currentUser();
        if (currentUser != null) {
            exposeUserId(request, currentUser);
            MDC.put(USER_ID_MDC_KEY, currentUser.userId());
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            CurrentUser finalCurrentUser = currentUser();
            if (finalCurrentUser != null) {
                exposeUserId(request, finalCurrentUser);
            }
            MDC.remove(USER_ID_MDC_KEY);
        }
    }

    /**
     * 从当前线程 SecurityContext 提取已认证 CurrentUser。
     *
     * @return 有效 CurrentUser；匿名、未认证或 Principal 类型不匹配时返回 {@code null}
     */
    private CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                        && authentication.isAuthenticated()
                        && authentication.getPrincipal() instanceof CurrentUser currentUser
                ? currentUser
                : null;
    }

    /**
     * 将用户标识写入请求属性，供外层 HTTP 出站审计在过滤器结束后读取。
     *
     * @param request 当前 HTTP 请求
     * @param currentUser 当前认证用户
     */
    private void exposeUserId(HttpServletRequest request, CurrentUser currentUser) {
        request.setAttribute(
                RequestAuditLoggingFilter.AUTHENTICATED_USER_ID_ATTRIBUTE,
                currentUser.userId());
    }
}
