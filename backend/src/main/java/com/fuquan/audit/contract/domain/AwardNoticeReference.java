package com.fuquan.audit.contract.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * 表示 F02 审查所引用的结构化中标依据。
 *
 * @param id 中标依据唯一标识
 * @param contractId 关联的合同标识
 * @param noticeNo 中标通知书编号
 * @param projectName 中标项目名称
 * @param winningBidder 中标供应商名称
 * @param winningBidderCreditCode 中标供应商统一社会信用代码，可为空
 * @param winningAmount 中标金额
 * @param winningDate 中标日期
 * @param sourceRef 中标依据来源引用，可为空
 */
public record AwardNoticeReference(
        UUID id,
        UUID contractId,
        String noticeNo,
        String projectName,
        String winningBidder,
        String winningBidderCreditCode,
        Money winningAmount,
        LocalDate winningDate,
        String sourceRef) {

    /**
     * 校验中标依据的必填标识、主体、金额和日期，阻止不完整依据进入确定性规则。
     *
     * @throws NullPointerException 必填字段为 {@code null} 时抛出
     * @throws IllegalArgumentException 必填文本为空白时抛出
     */
    public AwardNoticeReference {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(contractId, "contractId must not be null");
        noticeNo = requireNotBlank(noticeNo, "noticeNo");
        projectName = requireNotBlank(projectName, "projectName");
        winningBidder = requireNotBlank(winningBidder, "winningBidder");
        Objects.requireNonNull(winningAmount, "winningAmount must not be null");
        Objects.requireNonNull(winningDate, "winningDate must not be null");
    }

    /**
     * 校验领域对象中的必填文本并原样返回。
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
