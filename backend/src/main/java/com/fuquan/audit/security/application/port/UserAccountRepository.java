package com.fuquan.audit.security.application.port;

import com.fuquan.audit.security.domain.UserAccount;
import java.time.Instant;
import java.util.Optional;

/**
 * 系统用户账号和登录安全状态的 Repository Port。
 *
 * <p>正式账号、密码 Hash、角色、失败次数和锁定状态以 DM8 为事实源，不存放于 Redis Session。</p>
 */
public interface UserAccountRepository extends UserAccountRoleQueryPort {

    /**
     * 按规范化用户名读取账号。
     *
     * @param normalizedUsername 已通过 UsernameNormalizer 处理的用户名
     * @return 找到的账号；不存在时返回空
     */
    Optional<UserAccount> findByUsername(String normalizedUsername);

    /**
     * 原子记录一次密码失败，并在达到阈值时锁定账号。
     *
     * @param userId 用户标识
     * @param maxFailedAttempts 最大连续失败次数
     * @param lockUntil 达到阈值时的锁定截止时间
     * @param occurredAt 本次失败时间
     * @return 更新后的账号快照
     */
    UserAccount recordFailedLogin(
            String userId,
            int maxFailedAttempts,
            Instant lockUntil,
            Instant occurredAt);

    /**
     * 原子记录登录成功并清零失败状态。
     *
     * @param userId 用户标识
     * @param occurredAt 登录成功时间
     * @return 更新后的账号快照
     */
    UserAccount recordSuccessfulLogin(String userId, Instant occurredAt);

    /**
     * 在锁定期已过时恢复账号为可认证状态。
     *
     * @param userId 用户标识
     * @param occurredAt 检查并解锁的业务时间
     * @return 解锁后的账号；仍处于有效锁定期时保持原状态
     */
    UserAccount unlockIfExpired(String userId, Instant occurredAt);
}
