package com.fuquan.audit.security.infra.memory;

import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.security.application.port.UserAccountRepository;
import com.fuquan.audit.security.application.port.UserIdentityQueryPort;
import com.fuquan.audit.security.application.port.UserRoleAssignmentPort;
import com.fuquan.audit.security.config.LocalAccountProperties;
import com.fuquan.audit.security.domain.UserAccount;
import com.fuquan.audit.security.domain.UserAccountStatus;
import com.fuquan.audit.security.domain.UsernameNormalizer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * local profile 的并发安全用户账号内存适配器。
 *
 * <p>实现模拟用户名唯一索引、失败计数、锁定和乐观版本递增；正式用户账号和安全状态仍以 DM8 为事实源。</p>
 */
@Repository
@Profile("local")
public class InMemoryUserAccountRepository implements UserAccountRepository, UserIdentityQueryPort, UserRoleAssignmentPort {

    private static final Set<String> ACTIVE_ROLE_CODES = Set.of(
            "SYSTEM_ADMIN", "PROCUREMENT_MANAGER", "AUDITOR", "LEGAL",
            "PROCUREMENT_APPLICANT", "PROCUREMENT_REVIEWER", "FINANCE_REVIEWER",
            "PROCUREMENT_LEADER", "PROCUREMENT_COMMITTEE");

    private final ConcurrentMap<String, UserAccount> accountsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> idsByUsername = new ConcurrentHashMap<>();
    private final FlowDebugLogger flowDebugLogger;

    /**
     * 创建 local 用户仓储，并在完整配置时初始化受控首账号。
     *
     * @param localAccountProperties local 首账号外部配置
     * @param flowDebugLogger 分层流程调试日志记录器
     */
    public InMemoryUserAccountRepository(
            LocalAccountProperties localAccountProperties,
            FlowDebugLogger flowDebugLogger) {
        this.flowDebugLogger = flowDebugLogger;
        seedConfiguredAccount(localAccountProperties);
        localAccountProperties.getApprovalUsers().forEach(this::seedApprovalUser);
    }

    /**
     * 通过规范化用户名索引读取 local 账号并记录仓储耗时。
     *
     * @param normalizedUsername 已规范化或可再次规范化的用户名
     * @return 找到的账号；不存在时返回空
     */
    @Override
    public Optional<UserAccount> findByUsername(String normalizedUsername) {
        long startedAt = System.nanoTime();
        String id = idsByUsername.get(UsernameNormalizer.normalize(normalizedUsername));
        UserAccount account = id == null ? null : accountsById.get(id);
        flowDebugLogger.log(
                "USER_REPOSITORY_READ",
                "AUTH",
                "REPOSITORY",
                "findByUsername",
                Map.of(
                        "repository", "UserAccountRepository",
                        "durationMs", elapsedMs(startedAt),
                        "outcome", account == null ? "NOT_FOUND" : "FOUND"));
        return Optional.ofNullable(account);
    }

    @Override
    public Optional<String> findDisplayNameByUserId(String userId) {
        return Optional.ofNullable(accountsById.get(userId)).map(UserAccount::realName);
    }

    @Override
    public Optional<UserAccount> findFirstActiveByRole(String roleCode) {
        return accountsById.values().stream()
                .filter(account -> account.status() == UserAccountStatus.ACTIVE)
                .filter(account -> account.roles().contains(roleCode))
                .sorted(java.util.Comparator.comparing(UserAccount::id))
                .findFirst();
    }

    @Override
    public Optional<UserAccount> findActiveById(String userId) {
        return Optional.ofNullable(accountsById.get(userId))
                .filter(account -> account.status() == UserAccountStatus.ACTIVE);
    }

    @Override
    public boolean isActiveRole(String roleCode) {
        return ACTIVE_ROLE_CODES.contains(roleCode);
    }

    @Override
    public boolean hasRole(String userId, String roleCode) {
        return findActiveById(userId).map(account -> account.roles().contains(roleCode)).orElse(false);
    }

    @Override
    public void assignRole(String userId, String roleCode) {
        accountsById.compute(userId, (id, current) -> {
            UserAccount account = requireAccount(current);
            if (account.status() != UserAccountStatus.ACTIVE) {
                throw new IllegalStateException("disabled account cannot receive a role");
            }
            Set<String> roles = new java.util.HashSet<>(account.roles());
            roles.add(roleCode);
            return new UserAccount(account.id(), account.username(), account.passwordHash(), account.realName(),
                    account.status(), account.failedLoginCount(), account.lockedUntil(), account.lastLoginAt(),
                    account.passwordChangedAt(), account.createdAt(), account.updatedAt(), account.versionNo() + 1,
                    roles);
        });
    }

    @Override
    public void removeRole(String userId, String roleCode) {
        accountsById.compute(userId, (id, current) -> {
            UserAccount account = requireAccount(current);
            Set<String> roles = new java.util.HashSet<>(account.roles());
            roles.remove(roleCode);
            return new UserAccount(account.id(), account.username(), account.passwordHash(), account.realName(),
                    account.status(), account.failedLoginCount(), account.lockedUntil(), account.lastLoginAt(),
                    account.passwordChangedAt(), account.createdAt(), Instant.now(), account.versionNo() + 1, roles);
        });
    }

    @Override
    public List<String> findActiveRoleCodesByUserId(String userId) {
        return findActiveById(userId).map(UserAccount::roles)
                .orElseGet(Set::of).stream().sorted().toList();
    }

    /**
     * 原子增加失败次数，并在达到阈值时锁定 local 账号。
     *
     * @param userId 用户标识
     * @param maxFailedAttempts 最大连续失败次数
     * @param lockUntil 新锁定的截止时间
     * @param occurredAt 本次失败时间
     * @return 更新后的账号快照
     * @throws IllegalArgumentException 最大失败次数非正数时抛出
     * @throws IllegalStateException 账号不存在或已禁用时抛出
     */
    @Override
    public UserAccount recordFailedLogin(
            String userId,
            int maxFailedAttempts,
            Instant lockUntil,
            Instant occurredAt) {
        if (maxFailedAttempts <= 0) {
            throw new IllegalArgumentException("maxFailedAttempts must be positive");
        }
        return accountsById.compute(userId, (id, current) -> {
            UserAccount account = requireAccount(current);
            if (account.status() == UserAccountStatus.DISABLED) {
                throw new IllegalStateException("disabled account cannot record password failure");
            }
            int failedCount = account.failedLoginCount() + 1;
            boolean locked = failedCount >= maxFailedAttempts;
            Instant effectiveLockedUntil = account.lockedUntil();
            if (locked && effectiveLockedUntil == null) {
                effectiveLockedUntil = Objects.requireNonNull(lockUntil, "lockUntil must not be null");
            }
            return copy(
                    account,
                    locked ? UserAccountStatus.LOCKED : UserAccountStatus.ACTIVE,
                    failedCount,
                    effectiveLockedUntil,
                    account.lastLoginAt(),
                    occurredAt);
        });
    }

    /**
     * 原子记录 local 账号登录成功并清除失败锁定信息。
     *
     * @param userId 用户标识
     * @param occurredAt 登录成功时间
     * @return 更新后的 ACTIVE 账号快照
     * @throws IllegalStateException 账号不存在或不是 ACTIVE 时抛出
     */
    @Override
    public UserAccount recordSuccessfulLogin(String userId, Instant occurredAt) {
        return accountsById.compute(userId, (id, current) -> {
            UserAccount account = requireAccount(current);
            if (account.status() != UserAccountStatus.ACTIVE) {
                throw new IllegalStateException("only an active account can record login success");
            }
            return copy(account, UserAccountStatus.ACTIVE, 0, null, occurredAt, occurredAt);
        });
    }

    /**
     * 在锁定截止时间已到时原子恢复 local 账号。
     *
     * @param userId 用户标识
     * @param occurredAt 本次解锁判断时间
     * @return 解锁后的账号，或无需解锁时的原账号
     * @throws IllegalStateException 账号不存在时抛出
     */
    @Override
    public UserAccount unlockIfExpired(String userId, Instant occurredAt) {
        return accountsById.compute(userId, (id, current) -> {
            UserAccount account = requireAccount(current);
            if (account.status() == UserAccountStatus.LOCKED
                    && account.lockedUntil() != null
                    && !account.lockedUntil().isAfter(occurredAt)) {
                return copy(account, UserAccountStatus.ACTIVE, 0, null, account.lastLoginAt(), occurredAt);
            }
            return account;
        });
    }

    /**
     * 直接写入 local 账号，作为受控启动和自动化测试接缝。
     *
     * <p>该方法有意不属于应用层 UserAccountRepository Port，正式环境不能依赖此入口创建账号。</p>
     *
     * @param account 待写入的完整账号快照
     * @throws NullPointerException 账号为空时抛出
     */
    public void seed(UserAccount account) {
        Objects.requireNonNull(account, "account must not be null");
        accountsById.put(account.id(), account);
        idsByUsername.put(account.username(), account.id());
    }

    /**
     * 在用户名和密码 Hash 均配置时初始化 local 首账号。
     *
     * @param properties local 首账号外部配置
     * @throws IllegalArgumentException 配置启用但标识或显示名称缺失时抛出
     */
    private void seedConfiguredAccount(LocalAccountProperties properties) {
        if (isBlank(properties.getUsername()) || isBlank(properties.getPasswordHash())) {
            return;
        }
        Instant now = Instant.now();
        seed(new UserAccount(
                requireConfigured(properties.getId(), "local account id"),
                properties.getUsername(),
                properties.getPasswordHash(),
                requireConfigured(properties.getRealName(), "local account real name"),
                UserAccountStatus.ACTIVE,
                0,
                null,
                null,
                now,
                now,
                now,
                0,
                Set.copyOf(properties.getRoles())));
    }

    private void seedApprovalUser(LocalAccountProperties.ApprovalUserProperties properties) {
        if (isBlank(properties.getUsername()) || isBlank(properties.getPasswordHash())) {
            return;
        }
        Instant now = Instant.now();
        seed(new UserAccount(
                requireConfigured(properties.getId(), "approval test user id"),
                properties.getUsername(),
                properties.getPasswordHash(),
                requireConfigured(properties.getRealName(), "approval test user real name"),
                UserAccountStatus.ACTIVE,
                0,
                null,
                null,
                now,
                now,
                now,
                0,
                Set.copyOf(properties.getRoles())));
    }

    /**
     * 复制账号并递增版本，集中保持未变字段和角色不丢失。
     *
     * @param account 原账号快照
     * @param status 新账号状态
     * @param failedLoginCount 新连续失败次数
     * @param lockedUntil 新锁定截止时间
     * @param lastLoginAt 新最近登录时间
     * @param updatedAt 新更新时间
     * @return 版本号递增的账号快照
     */
    private UserAccount copy(
            UserAccount account,
            UserAccountStatus status,
            int failedLoginCount,
            Instant lockedUntil,
            Instant lastLoginAt,
            Instant updatedAt) {
        return new UserAccount(
                account.id(),
                account.username(),
                account.passwordHash(),
                account.realName(),
                status,
                failedLoginCount,
                lockedUntil,
                lastLoginAt,
                account.passwordChangedAt(),
                account.createdAt(),
                Objects.requireNonNull(updatedAt, "updatedAt must not be null"),
                account.versionNo() + 1,
                account.roles());
    }

    /**
     * 将 Map 中不存在的账号转换为明确状态错误。
     *
     * @param account Map 原子更新回调读取的当前账号
     * @return 已存在账号
     * @throws IllegalStateException 账号不存在时抛出
     */
    private UserAccount requireAccount(UserAccount account) {
        if (account == null) {
            throw new IllegalStateException("user account does not exist");
        }
        return account;
    }

    /**
     * 判断可选 local 配置文本是否缺失。
     *
     * @param value 配置文本
     * @return 为 {@code null} 或空白时返回 {@code true}
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 校验启用 local 首账号后必须提供的字段。
     *
     * @param value 配置值
     * @param field 用于异常信息的字段名称
     * @return 非空白配置值
     * @throws IllegalArgumentException 配置值缺失时抛出
     */
    private static String requireConfigured(String value, String field) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(field + " must not be blank when local account is configured");
        }
        return value;
    }

    /**
     * 将单调时钟起点换算为仓储耗时毫秒数。
     *
     * @param startedAt {@link System#nanoTime()} 取得的起点
     * @return 非墙钟语义的耗时毫秒数
     */
    private static long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
