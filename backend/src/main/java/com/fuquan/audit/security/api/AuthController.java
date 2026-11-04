package com.fuquan.audit.security.api;

import com.fuquan.audit.common.api.ApiResponse;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.security.application.AccountAuthenticationService;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.security.infra.RequestMetadataFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 暴露 Session Cookie + CSRF 认证 API。
 *
 * <p>控制器显式建立和保存 Spring SecurityContext；local 使用 Servlet 内存 HttpSession，
 * dev29/customer 使用 Redis Spring Session，但认证 API 契约保持一致。</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final LogoutHandler logoutHandler;
    private final AuthContext authContext;
    private final AccountAuthenticationService authenticationService;
    private final RequestMetadataFactory requestMetadataFactory;
    private final CsrfTokenRepository csrfTokenRepository;
    private final FlowDebugLogger flowDebugLogger;

    /**
     * 创建认证 HTTP 入口并注入显式 Session 生命周期协作者。
     *
     * @param authenticationManager Spring Security 认证管理器
     * @param securityContextRepository SecurityContext 的 Session 存储边界
     * @param sessionAuthenticationStrategy Session fixation 与登录后 CSRF 策略
     * @param logoutHandler Session 失效和认证清理处理器
     * @param authContext 当前用户读取端口
     * @param authenticationService 账号认证与安全审计服务
     * @param requestMetadataFactory 请求级审计元数据工厂
     * @param csrfTokenRepository Session CSRF Token 仓储
     * @param flowDebugLogger 分层流程调试日志记录器
     */
    public AuthController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            LogoutHandler logoutHandler,
            AuthContext authContext,
            AccountAuthenticationService authenticationService,
            RequestMetadataFactory requestMetadataFactory,
            CsrfTokenRepository csrfTokenRepository,
            FlowDebugLogger flowDebugLogger) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.logoutHandler = logoutHandler;
        this.authContext = authContext;
        this.authenticationService = authenticationService;
        this.requestMetadataFactory = requestMetadataFactory;
        this.csrfTokenRepository = csrfTokenRepository;
        this.flowDebugLogger = flowDebugLogger;
    }

    /**
     * 返回当前 Session 对应的 CSRF Header 名称、参数名和 Token。
     *
     * @param request 当前 HTTP 请求
     * @return 公共响应封装的 CSRF 信息
     */
    @GetMapping("/csrf")
    public ApiResponse<CsrfResponse> csrf(HttpServletRequest request) {
        CsrfToken csrfToken = csrfTokenRepository.loadToken(request);
        if (csrfToken == null) {
            csrfToken = csrfTokenRepository.generateToken(request);
            csrfTokenRepository.saveToken(csrfToken, request, null);
        }
        return ApiResponse.success(new CsrfResponse(
                csrfToken.getHeaderName(),
                csrfToken.getParameterName(),
                csrfToken.getToken()));
    }

    /**
     * 认证账号密码并显式建立新的 Session SecurityContext。
     *
     * <p>LoginRequestMetadata 仅服务当前认证和 LOGIN_SUCCESS/LOGIN_FAILED 审计。认证完成后，
     * 在执行 Session fixation/CSRF 策略和保存 SecurityContext 之前清除最终 Authentication.details，
     * 从生命周期边界上保证请求级元数据不进入长期 Session 状态。</p>
     *
     * @param loginRequest 通过格式校验的账号密码请求
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应，用于 Session 认证策略和上下文保存
     * @return 已认证用户的非敏感身份与角色
     */
    @PostMapping("/login")
    public ApiResponse<AuthenticatedUserResponse> login(
            @Valid @RequestBody LoginRequest loginRequest,
            HttpServletRequest request,
            HttpServletResponse response) {
        UsernamePasswordAuthenticationToken authenticationRequest =
                UsernamePasswordAuthenticationToken.unauthenticated(
                        loginRequest.username(), loginRequest.password());
        authenticationRequest.setDetails(requestMetadataFactory.from(request));
        Authentication authentication = authenticationManager.authenticate(authenticationRequest);
        if (authentication instanceof AbstractAuthenticationToken token) {
            token.setDetails(null);
        }

        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        CurrentUser currentUser = (CurrentUser) authentication.getPrincipal();
        flowDebugLogger.log(
                "SESSION_CREATED",
                "AUTH",
                "WEB",
                "LOGIN",
                Map.of("userId", currentUser.userId()));
        return ApiResponse.success(AuthenticatedUserResponse.from(currentUser));
    }

    /**
     * 从当前有效 Session 恢复并返回认证用户。
     *
     * @return 当前用户的非敏感身份与角色
     * @throws BusinessException 当前请求没有有效认证用户时抛出 UNAUTHENTICATED
     */
    @GetMapping("/me")
    public ApiResponse<AuthenticatedUserResponse> me() {
        CurrentUser currentUser = requireCurrentUser();
        return ApiResponse.success(AuthenticatedUserResponse.from(currentUser));
    }

    /**
     * 记录退出审计、失效当前 Session 并清空线程 SecurityContext。
     *
     * @param request 当前 HTTP 请求，用于退出审计元数据和 Session 失效
     * @param response 当前 HTTP 响应
     * @return 状态为 {@code logged_out} 的公共响应
     * @throws BusinessException 当前请求没有有效认证用户时抛出 UNAUTHENTICATED
     */
    @PostMapping("/logout")
    public ApiResponse<Map<String, String>> logout(
            HttpServletRequest request,
            HttpServletResponse response) {
        CurrentUser currentUser = requireCurrentUser();
        authenticationService.auditLogout(currentUser, requestMetadataFactory.from(request));
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        logoutHandler.logout(request, response, authentication);
        SecurityContextHolder.clearContext();
        return ApiResponse.success(Map.of("status", "logged_out"));
    }

    /**
     * 获取当前认证用户，缺失时转换为统一业务异常。
     *
     * @return 当前有效 Session 用户
     * @throws BusinessException 当前请求未认证时抛出 UNAUTHENTICATED
     */
    private CurrentUser requireCurrentUser() {
        return authContext.currentUser().orElseThrow(() ->
                new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
    }

    /**
     * 登录请求 DTO。
     *
     * @param username 账号名，只允许字母、数字、点、下划线和连字符
     * @param password 原始密码，仅用于本次认证且不得记录
     */
    public record LoginRequest(
            @NotBlank(message = "username must not be blank")
            @Size(max = 128, message = "username must not exceed 128 characters")
            @Pattern(regexp = "[A-Za-z0-9._-]+", message = "username format is invalid")
            String username,
            @NotBlank(message = "password must not be blank")
            @Size(max = 1024, message = "password is too long")
            String password) {

        /**
         * 返回隐藏账号和密码内容的安全诊断文本。
         *
         * @return 不包含认证凭据的请求摘要
         */
        @Override
        public String toString() {
            return "LoginRequest[username=***, password=***]";
        }
    }

    /**
     * CSRF 获取接口响应。
     *
     * @param headerName 客户端提交 Token 时使用的 Header 名称
     * @param parameterName 表单参数名称
     * @param token 当前 Session 的 CSRF Token
     */
    public record CsrfResponse(String headerName, String parameterName, String token) {
    }

    /**
     * 登录和当前用户接口返回的非敏感认证用户信息。
     *
     * @param userId 用户唯一标识
     * @param username 规范化用户名
     * @param realName 用户显示名称
     * @param roles 排序后的业务角色
     */
    public record AuthenticatedUserResponse(
            String userId,
            String username,
            String realName,
            List<String> roles) {

        /**
         * 将 Session CurrentUser 映射为稳定 API 响应并排序角色。
         *
         * @param user 当前认证用户
         * @return 不包含密码和 Session 数据的用户响应
         */
        static AuthenticatedUserResponse from(CurrentUser user) {
            return new AuthenticatedUserResponse(
                    user.userId(),
                    user.username(),
                    user.displayName(),
                    user.roles().stream().sorted().toList());
        }
    }
}
