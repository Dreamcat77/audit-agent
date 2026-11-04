package com.fuquan.audit.security.infra;

import com.fuquan.audit.security.application.AccountAuthenticationService;
import com.fuquan.audit.security.application.AuthenticationDecision;
import com.fuquan.audit.security.application.LoginRequestMetadata;
import com.fuquan.audit.security.domain.CurrentUser;
import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * 将 Spring Security 用户名密码认证请求适配到账号认证应用服务。
 *
 * <p>请求中的 LoginRequestMetadata 在本次调用中传给认证与审计服务；成功 token 只包含 CurrentUser 和角色权限，
 * 控制器在建立长期 Session 前进一步清理认证结果的 request-only details。</p>
 */
@Component
public class UserAccountAuthenticationProvider implements AuthenticationProvider {

    private final AccountAuthenticationService authenticationService;

    /**
     * 创建使用账号认证应用服务的 Provider。
     *
     * @param authenticationService 账号密码认证和审计服务
     */
    public UserAccountAuthenticationProvider(AccountAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * 认证用户名密码并将业务角色映射为 Spring Security Authority。
     *
     * @param authentication 未认证用户名密码 token，details 可携带当前请求元数据
     * @return Principal 为 CurrentUser、凭据为空的已认证 token
     * @throws AuthenticationException 认证失败时抛出统一 BadCredentialsException
     */
    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = authentication.getName();
        Object credentials = authentication.getCredentials();
        CharSequence password = credentials instanceof CharSequence value ? value : "";
        LoginRequestMetadata metadata = authentication.getDetails() instanceof LoginRequestMetadata value
                ? value
                : LoginRequestMetadata.empty();
        AuthenticationDecision decision = authenticationService.authenticate(username, password, metadata);
        if (!decision.authenticated()) {
            throw new BadCredentialsException("invalid username or password");
        }
        CurrentUser currentUser = decision.user();
        List<SimpleGrantedAuthority> authorities = currentUser.roles().stream()
                .sorted()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return UsernamePasswordAuthenticationToken.authenticated(currentUser, null, authorities);
    }

    /**
     * 判断该 Provider 是否支持用户名密码 token 及其子类。
     *
     * @param authentication 待判断认证 token 类型
     * @return 可由本 Provider 处理时为 {@code true}
     */
    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
