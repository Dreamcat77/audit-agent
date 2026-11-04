package com.fuquan.audit.security.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定账号认证失败锁定策略。
 *
 * <p>应用服务启动时会校验最大失败次数和锁定时长均为正值。</p>
 */
@ConfigurationProperties("audit.security.authentication")
public class AuthenticationProperties {

    private int maxFailedAttempts = 5;
    private Duration lockDuration = Duration.ofMinutes(15);

    /**
     * 返回触发账号锁定的最大连续失败次数。
     *
     * @return 正整数失败阈值
     */
    public int getMaxFailedAttempts() {
        return maxFailedAttempts;
    }

    /**
     * 供配置绑定设置最大连续失败次数。
     *
     * @param maxFailedAttempts 待校验的失败阈值
     */
    public void setMaxFailedAttempts(int maxFailedAttempts) {
        this.maxFailedAttempts = maxFailedAttempts;
    }

    /**
     * 返回账号达到失败阈值后的锁定时长。
     *
     * @return 正数锁定时长
     */
    public Duration getLockDuration() {
        return lockDuration;
    }

    /**
     * 供配置绑定设置账号锁定时长。
     *
     * @param lockDuration 待校验的锁定时长
     */
    public void setLockDuration(Duration lockDuration) {
        this.lockDuration = lockDuration;
    }
}
