package com.fuquan.audit.security.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 系统用户账号及其登录安全状态聚合。
 *
 * @param id 用户唯一标识
 * @param username 规范化用户名
 * @param passwordHash Spring Security delegating password Hash
 * @param realName 用户真实或显示名称
 * @param status 账号状态
 * @param failedLoginCount 连续失败次数
 * @param lockedUntil 锁定截止时间，永久锁定时可为空
 * @param lastLoginAt 最近成功登录时间，可为空
 * @param passwordChangedAt 最近密码变更时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param versionNo 乐观锁版本号
 * @param roles 不带 {@code ROLE_} 前缀的业务角色
 */
public record UserAccount(
        String id,
        String username,
        String passwordHash,
        String realName,
        UserAccountStatus status,
        int failedLoginCount,
        Instant lockedUntil,
        Instant lastLoginAt,
        Instant passwordChangedAt,
        Instant createdAt,
        Instant updatedAt,
        long versionNo,
        Set<String> roles) {

    /**
     * 规范化用户名、校验账号安全字段并建立角色不可变快照。
     *
     * @throws NullPointerException 必填字段为空时抛出
     * @throws IllegalArgumentException 用户名或必填文本非法、计数或版本号为负数时抛出
     */
    public UserAccount {
        id = requireNotBlank(id, "id");
        username = UsernameNormalizer.normalize(username);
        passwordHash = requireNotBlank(passwordHash, "passwordHash");
        realName = requireNotBlank(realName, "realName");
        Objects.requireNonNull(status, "status must not be null");
        if (failedLoginCount < 0) {
            throw new IllegalArgumentException("failedLoginCount must be non-negative");
        }
        Objects.requireNonNull(passwordChangedAt, "passwordChangedAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (versionNo < 0) {
            throw new IllegalArgumentException("versionNo must be non-negative");
        }
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
    }

    /**
     * 判断账号在指定业务时间是否仍处于有效锁定。
     *
     * <p>LOCKED 且没有截止时间表示持续锁定；有截止时间时，仅在截止时间之后才允许解锁流程处理。</p>
     *
     * @param now 判断锁定状态的业务时间
     * @return 当前仍应拒绝认证时为 {@code true}
     * @throws NullPointerException 业务时间为空时抛出
     */
    public boolean hasActiveLockAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return status == UserAccountStatus.LOCKED
                && (lockedUntil == null || lockedUntil.isAfter(now));
    }

    /**
     * 投影为允许进入 Session 的最小认证用户快照。
     *
     * @return 不含密码 Hash、失败次数和锁定信息的 CurrentUser
     */
    public CurrentUser toCurrentUser() {
        return new CurrentUser(id, username, realName, roles);
    }

    /**
     * 返回隐去密码 Hash 的安全诊断文本。
     *
     * @return 不暴露密码 Hash 的账号摘要
     */
    @Override
    public String toString() {
        return "UserAccount[id=" + id
                + ", username=" + username
                + ", passwordHash=***, status=" + status
                + ", failedLoginCount=" + failedLoginCount
                + ", versionNo=" + versionNo + "]";
    }

    /**
     * 校验账号领域中的必填文本并原样返回。
     *
     * @param value 待校验文本
     * @param fieldName 用于异常信息的字段名
     * @return 通过非空白校验的原文本
     * @throws NullPointerException 文本为 {@code null} 时抛出
     * @throws IllegalArgumentException 文本为空白时抛出
     */
    private static String requireNotBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
