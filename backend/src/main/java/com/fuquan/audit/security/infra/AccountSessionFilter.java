package com.fuquan.audit.security.infra;

import com.fuquan.audit.security.application.port.UserAccountRepository;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.security.domain.UserAccountStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

final class AccountSessionFilter extends OncePerRequestFilter {
    private final UserAccountRepository accounts;
    private final AuthenticationEntryPoint entryPoint;
    private final boolean persistedSessions;

    AccountSessionFilter(UserAccountRepository accounts, AuthenticationEntryPoint entryPoint, boolean persistedSessions) {
        this.accounts = accounts;
        this.entryPoint = entryPoint;
        this.persistedSessions = persistedSessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        var session = request.getSession(false);
        // Local servlet sessions cannot survive a release restart. Persistent sessions from older releases
        // have no credential version and must sign in once after upgrade.
        if (session != null && auth != null && auth.getPrincipal() instanceof CurrentUser user
                && (user.sessionVersion() != null ||
                    persistedSessions && session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null)) {
            var account = accounts.findSessionAccount(user.userId()).orElse(null);
            if (account == null || account.status() == UserAccountStatus.DISABLED
                    || user.sessionVersion() == null || !user.sessionVersion().equals(account.version())) {
                SecurityContextHolder.clearContext();
                session.invalidate();
                if (!"/api/v1/auth/csrf".equals(request.getServletPath())
                        && !"/api/v1/auth/csrf".equals(request.getRequestURI())) {
                    entryPoint.commence(request, response, new BadCredentialsException("session revoked"));
                    return;
                }
            } else if (!user.displayName().equals(account.displayName())) {
                var principal = new CurrentUser(user.userId(), user.username(), account.displayName(),
                        user.roles(), user.sessionVersion());
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, auth.getAuthorities()));
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }
}
