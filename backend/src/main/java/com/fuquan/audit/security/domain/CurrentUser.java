package com.fuquan.audit.security.domain;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.Set;

/**
 * 经认证后允许进入 SecurityContext/Session 的最小用户身份快照。
 *
 * <p>该对象只保存授权所需的稳定身份和角色，因此可由 local Servlet HttpSession 或 dev29/customer Redis Spring Session 持久化。</p>
 *
 * @param userId 用户唯一标识
 * @param username 规范化用户名
 * @param displayName 页面显示名称
 * @param roles 不带 {@code ROLE_} 前缀的业务角色集合
 */
public record CurrentUser(String userId, String username, String displayName, Set<String> roles)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 校验 Session 用户身份完整性并建立角色不可变快照。
     *
     * @throws NullPointerException 身份字段或角色集合为空时抛出
     */
    public CurrentUser {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
    }
}
