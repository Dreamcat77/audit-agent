package com.fuquan.audit.contract.application;

import java.util.Objects;
import java.util.UUID;

/**
 * 表示一次 F02 写操作的应用层命令及其请求审计元数据。
 *
 * @param contractId 待审查合同标识
 * @param idempotencyKey 客户端提供的写操作幂等键
 * @param requestId 当前 HTTP 请求标识
 * @param traceId 当前调用链标识
 * @param sourceIp 当前请求来源 IP，可为空
 */
public record ContractReviewCommand(
        UUID contractId,
        String idempotencyKey,
        String requestId,
        String traceId,
        String sourceIp) {

    /**
     * 校验合同、幂等键和可追踪审计字段的完整性。
     *
     * @throws NullPointerException 必填字段为 {@code null} 时抛出
     * @throws IllegalArgumentException 必填文本为空白时抛出
     */
    public ContractReviewCommand {
        Objects.requireNonNull(contractId, "contractId must not be null");
        idempotencyKey = requireNotBlank(idempotencyKey, "idempotencyKey");
        requestId = requireNotBlank(requestId, "requestId");
        traceId = requireNotBlank(traceId, "traceId");
    }

    /**
     * 校验命令中的必填文本并原样返回。
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
