package com.fuquan.audit.contract.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * F02 使用的合同聚合快照，包含合同主体、金额、明细和中标依据。
 *
 * <p>该对象承载一次确定性审查所需的结构化事实，不依赖 AI 或 OCR 才能执行五条 F02 规则。</p>
 *
 * @param id 合同唯一标识
 * @param contractNo 合同业务编号
 * @param contractName 合同名称
 * @param contractType 合同类型
 * @param partyA 甲方名称
 * @param partyB 乙方名称
 * @param partyBCreditCode 乙方统一社会信用代码，可为空
 * @param contractAmount 合同金额
 * @param amountInWords 合同金额中文大写，可为空
 * @param status 合同状态
 * @param versionNo 合同数据版本号
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param lineItems 合同明细项快照
 * @param awardNotice 关联中标依据，可为空
 */
public record Contract(
        UUID id,
        String contractNo,
        String contractName,
        String contractType,
        String partyA,
        String partyB,
        String partyBCreditCode,
        Money contractAmount,
        String amountInWords,
        String status,
        long versionNo,
        Instant createdAt,
        Instant updatedAt,
        List<ContractLineItem> lineItems,
        AwardNoticeReference awardNotice) {

    /**
     * 校验合同聚合的必填字段、版本号和明细集合，并对明细集合建立不可变快照。
     *
     * @throws NullPointerException 必填字段或明细集合为 {@code null} 时抛出
     * @throws IllegalArgumentException 必填文本为空白或版本号为负数时抛出
     */
    public Contract {
        Objects.requireNonNull(id, "id must not be null");
        contractNo = requireNotBlank(contractNo, "contractNo");
        contractName = requireNotBlank(contractName, "contractName");
        contractType = requireNotBlank(contractType, "contractType");
        partyA = requireNotBlank(partyA, "partyA");
        partyB = requireNotBlank(partyB, "partyB");
        Objects.requireNonNull(contractAmount, "contractAmount must not be null");
        status = requireNotBlank(status, "status");
        if (versionNo < 0) {
            throw new IllegalArgumentException("versionNo must be non-negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        lineItems = List.copyOf(Objects.requireNonNull(lineItems, "lineItems must not be null"));
    }

    /**
     * 校验合同领域中的必填文本并原样返回。
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
