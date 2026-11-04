package com.fuquan.audit.contract.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * 表示合同中的一条结构化金额明细，是行项目算术和明细合计规则的事实输入。
 *
 * @param id 明细唯一标识
 * @param contractId 所属合同标识
 * @param itemKey 明细业务键
 * @param itemName 明细名称
 * @param quantity 数量
 * @param unitPrice 单价
 * @param declaredAmount 合同声明的行金额
 * @param sortNo 展示及计算顺序号
 */
public record ContractLineItem(
        UUID id,
        UUID contractId,
        String itemKey,
        String itemName,
        ContractLineItemBasis basis,
        BigDecimal quantity,
        BigDecimal unitPrice,
        Money declaredAmount,
        int sortNo) {

    /**
     * 校验明细身份和金额字段，并禁止负数量、负单价或负声明金额进入审查。
     *
     * @throws NullPointerException 必填字段为 {@code null} 时抛出
     * @throws IllegalArgumentException 必填文本为空白或金额字段为负数时抛出
     */
    public ContractLineItem {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(contractId, "contractId must not be null");
        itemKey = requireNotBlank(itemKey, "itemKey");
        itemName = requireNotBlank(itemName, "itemName");
        Objects.requireNonNull(basis, "basis must not be null");
        Objects.requireNonNull(declaredAmount, "declaredAmount must not be null");
        if (basis == ContractLineItemBasis.QUANTITY_PRICE && (quantity == null || unitPrice == null)) {
            throw new IllegalArgumentException("quantity-price line requires quantity and unit price");
        }
        if (basis == ContractLineItemBasis.AMOUNT_ONLY && (quantity != null || unitPrice != null)) {
            throw new IllegalArgumentException("amount-only line cannot contain quantity or unit price");
        }
        if ((quantity != null && quantity.signum() < 0) || (unitPrice != null && unitPrice.signum() < 0)
                || declaredAmount.amount().signum() < 0) {
            throw new IllegalArgumentException("line item amounts must be non-negative");
        }
    }

    /** Backward-compatible constructor for the historical quantity/price shape. */
    public ContractLineItem(UUID id, UUID contractId, String itemKey, String itemName,
            BigDecimal quantity, BigDecimal unitPrice, Money declaredAmount, int sortNo) {
        this(id, contractId, itemKey, itemName, ContractLineItemBasis.QUANTITY_PRICE,
                quantity, unitPrice, declaredAmount, sortNo);
    }

    /**
     * 按数量乘以单价计算规则期望的行金额。
     *
     * @return 未经过浮点转换的精确十进制行金额
     */
    public BigDecimal calculatedAmount() {
        if (basis != ContractLineItemBasis.QUANTITY_PRICE) {
            throw new IllegalStateException("amount-only line has no calculated amount");
        }
        return quantity.multiply(unitPrice);
    }

    /**
     * 校验行项目中的必填文本并原样返回。
     *
     * @param value 待校验文本
     * @param field 用于异常信息的字段名
     * @return 通过非空白校验的原文本
     * @throws NullPointerException 文本为 {@code null} 时抛出
     * @throws IllegalArgumentException 文本为空白时抛出
     */
    private static String requireNotBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
