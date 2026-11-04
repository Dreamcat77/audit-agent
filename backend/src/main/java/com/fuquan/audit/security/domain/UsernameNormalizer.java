package com.fuquan.audit.security.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 统一用户名的存储和查询规范。
 *
 * <p>用户名去除首尾空白、按 ROOT locale 转为小写，并限制为安全字符集合，避免认证查询出现大小写和区域差异。</p>
 */
public final class UsernameNormalizer {

    private static final Pattern VALID_USERNAME = Pattern.compile("^[a-z0-9._-]{1,128}$");

    /**
     * 禁止实例化仅提供用户名规范化算法的工具类。
     */
    private UsernameNormalizer() {
    }

    /**
     * 将用户输入规范化为账号仓储使用的稳定键。
     *
     * @param username 用户输入用户名
     * @return 去首尾空白并转为小写的规范用户名
     * @throws NullPointerException 用户名为空时抛出
     * @throws IllegalArgumentException 规范化结果不符合长度或字符约束时抛出
     */
    public static String normalize(String username) {
        Objects.requireNonNull(username, "username must not be null");
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        if (!VALID_USERNAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("username format is invalid");
        }
        return normalized;
    }
}
