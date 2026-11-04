package com.fuquan.audit.security.application;

import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.security.application.port.LoginAuditEvent;
import com.fuquan.audit.security.application.port.LoginAuditPort;
import com.fuquan.audit.security.application.port.UserAccountRepository;
import com.fuquan.audit.security.config.AuthenticationProperties;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.security.domain.LoginAuditEventType;
import com.fuquan.audit.security.domain.LoginAuditResult;
import com.fuquan.audit.security.domain.LoginFailureReason;
import com.fuquan.audit.security.domain.UserAccount;
import com.fuquan.audit.security.domain.UserAccountStatus;
import com.fuquan.audit.security.domain.UsernameNormalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 执行账号密码认证、失败计数/锁定和长期登录安全审计。
 *
 * <p>账号状态更新与对应审计事实位于同一事务；未知用户仍执行一次虚拟密码 Hash 比较，以降低用户名枚举的时序差异。</p>
 */
@Service
public class AccountAuthenticationService {

    private final UserAccountRepository userAccountRepository;
    private final LoginAuditPort loginAuditPort;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationProperties properties;
    private final Clock clock;
    private final FlowDebugLogger flowDebugLogger;
    private final String dummyPasswordHash;

    /**
     * 创建认证应用服务并校验锁定策略配置。
     *
     * @param userAccountRepository 用户账号持久化端口
     * @param loginAuditPort 只追加登录安全审计端口
     * @param passwordEncoder Spring Security 密码编码器
     * @param properties 失败次数和锁定时长配置
     * @param clock 认证业务时间来源
     * @param flowDebugLogger 分层流程调试日志记录器
     * @throws IllegalArgumentException 最大失败次数或锁定时长配置非法时抛出
     */
    public AccountAuthenticationService(
            UserAccountRepository userAccountRepository,
            LoginAuditPort loginAuditPort,
            PasswordEncoder passwordEncoder,
            AuthenticationProperties properties,
            Clock clock,
            FlowDebugLogger flowDebugLogger) {
        this.userAccountRepository = userAccountRepository;
        this.loginAuditPort = loginAuditPort;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
        this.flowDebugLogger = flowDebugLogger;
        validateProperties(properties);
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 在事务中认证账号密码并更新账号安全状态及登录审计。
     *
     * <p>{@code metadata} 仅属于当前登录请求，用于 LOGIN_SUCCESS、LOGIN_FAILED 和 ACCOUNT_LOCKED 审计，
     * 不属于认证用户的长期 Session 状态。</p>
     *
     * @param username 用户输入的账号名
     * @param rawPassword 用户输入的原始密码，仅用于本次密码校验
     * @param metadata 当前登录请求的 RequestId、TraceId 和来源 IP，可为空
     * @return 不泄露账号存在性或具体状态的认证决策
     * @throws NullPointerException 原始密码为空时抛出
     * @throws IllegalArgumentException 用户名格式非法时抛出
     */
    @Transactional
    public AuthenticationDecision authenticate(
            String username,
            CharSequence rawPassword,
            LoginRequestMetadata metadata) {
        Objects.requireNonNull(rawPassword, "rawPassword must not be null");
        LoginRequestMetadata safeMetadata = metadata == null ? LoginRequestMetadata.empty() : metadata;
        String normalizedUsername = UsernameNormalizer.normalize(username);
        Instant now = clock.instant();
        flowDebugLogger.log(
                "APPLICATION_START",
                "AUTH",
                "APPLICATION",
                "LOGIN");
        flowDebugLogger.log(
                "AUTH_ATTEMPT",
                "AUTH",
                "APPLICATION",
                "LOGIN",
                Map.of("username", "***"));

        UserAccount account = userAccountRepository.findByUsername(normalizedUsername).orElse(null);
        if (account == null) {
            passwordEncoder.matches(rawPassword, dummyPasswordHash);
            appendFailureAudit(null, normalizedUsername, LoginAuditEventType.LOGIN_FAILED,
                    LoginFailureReason.BAD_CREDENTIALS, safeMetadata, now);
            return failure(LoginFailureReason.BAD_CREDENTIALS);
        }

        if (account.status() == UserAccountStatus.LOCKED && !account.hasActiveLockAt(now)) {
            account = userAccountRepository.unlockIfExpired(account.id(), now);
        }

        boolean passwordMatches = passwordEncoder.matches(rawPassword, account.passwordHash());
        if (account.status() == UserAccountStatus.DISABLED) {
            appendFailureAudit(account.id(), account.username(), LoginAuditEventType.LOGIN_FAILED,
                    LoginFailureReason.DISABLED, safeMetadata, now);
            return failure(LoginFailureReason.DISABLED);
        }
        if (account.hasActiveLockAt(now)) {
            appendFailureAudit(account.id(), account.username(), LoginAuditEventType.LOGIN_FAILED,
                    LoginFailureReason.LOCKED, safeMetadata, now);
            return failure(LoginFailureReason.LOCKED);
        }
        if (!passwordMatches) {
            UserAccount updated = userAccountRepository.recordFailedLogin(
                    account.id(),
                    properties.getMaxFailedAttempts(),
                    now.plus(properties.getLockDuration()),
                    now);
            appendFailureAudit(updated.id(), updated.username(), LoginAuditEventType.LOGIN_FAILED,
                    LoginFailureReason.BAD_CREDENTIALS, safeMetadata, now);
            if (updated.status() == UserAccountStatus.LOCKED
                    && updated.failedLoginCount() == properties.getMaxFailedAttempts()) {
                appendFailureAudit(updated.id(), updated.username(), LoginAuditEventType.ACCOUNT_LOCKED,
                        LoginFailureReason.LOCKED, safeMetadata, now);
            }
            return failure(LoginFailureReason.BAD_CREDENTIALS);
        }

        UserAccount updated = userAccountRepository.recordSuccessfulLogin(account.id(), now);
        appendSuccessAudit(updated, LoginAuditEventType.LOGIN_SUCCESS, safeMetadata, now);
        CurrentUser currentUser = updated.toCurrentUser();
        flowDebugLogger.log(
                "AUTH_SUCCESS",
                "AUTH",
                "APPLICATION",
                "LOGIN",
                Map.of("userId", currentUser.userId()));
        flowDebugLogger.log(
                "APPLICATION_EXIT",
                "AUTH",
                "APPLICATION",
                "LOGIN",
                Map.of("outcome", "SUCCESS"));
        return AuthenticationDecision.success(currentUser);
    }

    /**
     * 在事务中追加当前认证用户的退出安全审计。
     *
     * @param currentUser 即将退出的 Session 用户
     * @param metadata 当前退出请求的 RequestId、TraceId 和来源 IP，可为空
     * @throws NullPointerException 当前用户为空时抛出
     */
    @Transactional
    public void auditLogout(CurrentUser currentUser, LoginRequestMetadata metadata) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        LoginRequestMetadata safeMetadata = metadata == null ? LoginRequestMetadata.empty() : metadata;
        Instant now = clock.instant();
        loginAuditPort.append(new LoginAuditEvent(
                currentUser.userId(),
                currentUser.username(),
                LoginAuditEventType.LOGOUT,
                LoginAuditResult.SUCCESS,
                null,
                safeMetadata.requestId(),
                safeMetadata.traceId(),
                safeMetadata.sourceIp(),
                now));
        flowDebugLogger.log("LOGIN_AUDIT_INSERT", "AUTH", "REPOSITORY", "LOGOUT");
    }

    /**
     * 统一记录认证失败流程日志并创建失败决策。
     *
     * @param reason 内部认证失败原因
     * @return 不包含 CurrentUser 的失败决策
     */
    private AuthenticationDecision failure(LoginFailureReason reason) {
        flowDebugLogger.log(
                "AUTH_FAILED",
                "AUTH",
                "APPLICATION",
                "LOGIN",
                Map.of("outcome", reason.name()));
        flowDebugLogger.log(
                "APPLICATION_EXIT",
                "AUTH",
                "APPLICATION",
                "LOGIN",
                Map.of("outcome", "FAILURE"));
        return AuthenticationDecision.failure(reason);
    }

    /**
     * 追加不含密码和 Session 标识的成功登录安全审计。
     *
     * @param account 成功认证后的账号快照
     * @param eventType 成功事件类型
     * @param metadata 当前登录请求元数据
     * @param occurredAt 事件发生时间
     */
    private void appendSuccessAudit(
            UserAccount account,
            LoginAuditEventType eventType,
            LoginRequestMetadata metadata,
            Instant occurredAt) {
        loginAuditPort.append(new LoginAuditEvent(
                account.id(),
                account.username(),
                eventType,
                LoginAuditResult.SUCCESS,
                null,
                metadata.requestId(),
                metadata.traceId(),
                metadata.sourceIp(),
                occurredAt));
        flowDebugLogger.log("LOGIN_AUDIT_INSERT", "AUTH", "REPOSITORY", eventType.name());
    }

    /**
     * 追加失败登录或账号锁定安全审计。
     *
     * @param userId 已知账号标识，未知用户时为空
     * @param username 已规范化用户名
     * @param eventType 失败或锁定事件类型
     * @param reason 内部失败原因
     * @param metadata 当前登录请求元数据
     * @param occurredAt 事件发生时间
     */
    private void appendFailureAudit(
            String userId,
            String username,
            LoginAuditEventType eventType,
            LoginFailureReason reason,
            LoginRequestMetadata metadata,
            Instant occurredAt) {
        loginAuditPort.append(new LoginAuditEvent(
                userId,
                username,
                eventType,
                LoginAuditResult.FAILURE,
                reason,
                metadata.requestId(),
                metadata.traceId(),
                metadata.sourceIp(),
                occurredAt));
        flowDebugLogger.log("LOGIN_AUDIT_INSERT", "AUTH", "REPOSITORY", eventType.name());
    }

    /**
     * 在服务构造阶段校验认证锁定策略，避免以无效安全参数启动。
     *
     * @param properties 待校验认证配置
     * @throws IllegalArgumentException 最大失败次数非正数或锁定时长非正数时抛出
     */
    private static void validateProperties(AuthenticationProperties properties) {
        if (properties.getMaxFailedAttempts() <= 0) {
            throw new IllegalArgumentException("maxFailedAttempts must be positive");
        }
        if (properties.getLockDuration() == null
                || properties.getLockDuration().isZero()
                || properties.getLockDuration().isNegative()) {
            throw new IllegalArgumentException("lockDuration must be positive");
        }
    }
}
