package com.fuquan.audit.security.infra;

import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.security.config.CorsProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 配置基于 Session Cookie + CSRF 的 API 安全边界。
 *
 * <p>配置关闭表单登录、HTTP Basic 和框架默认 Logout，要求认证控制器显式保存 SecurityContext；
 * local 使用 Servlet HttpSession，dev29/customer 由 Spring Session Data Redis 接管相同 Session 契约。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class ApiSecurityConfiguration {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private final ApiAuthenticationEntryPoint authenticationEntryPoint;
    private final ApiAccessDeniedHandler accessDeniedHandler;

    /**
     * 创建使用统一 JSON 401/403 处理器的安全配置。
     *
     * @param authenticationEntryPoint 未认证响应入口
     * @param accessDeniedHandler 权限不足响应处理器
     */
    public ApiSecurityConfiguration(
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    /**
     * 构建 API FilterChain、授权规则、CSRF、CORS 和显式 SecurityContext 保存策略。
     *
     * @param http Spring Security HTTP 配置入口
     * @param csrfTokenRepository Session CSRF Token 仓储
     * @param securityContextRepository Session SecurityContext 仓储
     * @param corsConfigurationSource 可信前端来源配置
     * @return 应用于 API 的安全过滤器链
     * @throws Exception Spring Security 构建过滤器链失败时抛出
     */
    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            CsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/health",
                                "/actuator/health",
                                "/api/v1/auth/csrf",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .addFilterAfter(new AuthenticatedUserMdcFilter(), AnonymousAuthenticationFilter.class)
                .build();
    }

    /**
     * 提供支持 delegating Hash 前缀的密码编码器。
     *
     * @return Spring Security 委派密码编码器
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * 组装仅使用系统账号 Provider 的认证管理器。
     *
     * @param provider 系统账号认证 Provider
     * @return ProviderManager 认证入口
     */
    @Bean
    AuthenticationManager authenticationManager(UserAccountAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    /**
     * 提供将 Token 保存在 HttpSession 的 CSRF 仓储。
     *
     * @return Header 名称为 {@code X-CSRF-TOKEN} 的 Token 仓储
     */
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        return repository;
    }

    /**
     * 提供基于 HttpSession 的 SecurityContext 存储边界。
     *
     * @return local/Redis Spring Session 均可接管的上下文仓储
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * 组合登录后的 Session fixation 防护和 CSRF Token 更新策略。
     *
     * @param csrfTokenRepository 当前 Session CSRF Token 仓储
     * @return 先变更 Session ID、再更新 CSRF 的认证策略
     */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    /**
     * 提供失效 HttpSession 并清除认证的退出处理器。
     *
     * @return 显式 LogoutHandler
     */
    @Bean
    LogoutHandler logoutHandler() {
        SecurityContextLogoutHandler handler = new SecurityContextLogoutHandler();
        handler.setInvalidateHttpSession(true);
        handler.setClearAuthentication(true);
        return handler;
    }

    /**
     * 构建允许 Session Cookie 的精确 Origin CORS 配置。
     *
     * <p>允许的 Header 包含动态 CSRF、Idempotency-Key 和 Request-Id；携带凭据时明确拒绝通配 Origin。</p>
     *
     * @param properties 环境配置的可信前端来源
     * @return 仅应用于 {@code /api/**} 的 CORS 配置源
     * @throws IllegalArgumentException 来源列表包含通配符时抛出
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> allowedOrigins = properties.getAllowedOrigins().stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .map(String::trim)
                .toList();
        if (allowedOrigins.stream().anyMatch("*"::equals)) {
            throw new IllegalArgumentException("wildcard CORS origin is forbidden with credentials");
        }
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowCredentials(!allowedOrigins.isEmpty());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.CONTENT_TYPE,
                IDEMPOTENCY_KEY_HEADER,
                "X-CSRF-TOKEN",
                RequestIdFilter.HEADER_NAME));
        configuration.setExposedHeaders(List.of(RequestIdFilter.HEADER_NAME));
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
