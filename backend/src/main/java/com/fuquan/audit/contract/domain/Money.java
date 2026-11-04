package com.fuquan.audit.contract.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 人民币金额值对象，以 {@link BigDecimal} 保留精确十进制语义。
 *
 * <p>金额相等采用数值比较而非 scale 敏感的 {@code BigDecimal.equals}，用于 F02 零容差一致性判断。</p>
 */
public final class Money {

    private final BigDecimal amount;

    /**
     * 创建内部金额值并拒绝空值。
     *
     * @param amount 精确十进制金额
     * @throws NullPointerException 金额为空时抛出
     */
    private Money(BigDecimal amount) {
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
    }

    /**
     * 从精确十进制数创建金额值对象。
     *
     * @param amount 精确十进制金额
     * @return 保留原数值的金额对象
     * @throws NullPointerException 金额为空时抛出
     */
    public static Money of(BigDecimal amount) {
        return new Money(amount);
    }

    /**
     * 返回内部精确十进制金额。
     *
     * @return 金额数值
     */
    public BigDecimal amount() {
        return amount;
    }

    /**
     * 按数值语义比较金额，忽略小数位 scale 差异。
     *
     * @param other 待比较金额，可为空
     * @return 数值相等时为 {@code true}
     */
    public boolean sameValueAs(Money other) {
        return other != null && amount.compareTo(other.amount) == 0;
    }

    /**
     * 按金额数值语义判断对象相等，保证与 F02 比较规则一致。
     *
     * @param candidate 待比较对象
     * @return 对方为同数值金额时为 {@code true}
     */
    @Override
    public boolean equals(Object candidate) {
        return this == candidate
                || candidate instanceof Money other && sameValueAs(other);
    }

    /**
     * 生成与忽略 scale 的相等语义一致的哈希值。
     *
     * @return 规范化金额哈希值
     */
    @Override
    public int hashCode() {
        return amount.signum() == 0 ? 0 : amount.stripTrailingZeros().hashCode();
    }

    /**
     * 返回不使用科学计数法的金额文本，供证据和 API 值展示。
     *
     * @return 十进制金额文本
     */
    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
