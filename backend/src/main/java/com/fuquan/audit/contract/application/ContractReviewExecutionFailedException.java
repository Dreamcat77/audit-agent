package com.fuquan.audit.contract.application;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;

/**
 * 将 F02 执行或失败收口阶段的非业务异常统一映射为内部错误。
 *
 * <p>原始异常作为 cause 保留供服务端诊断，但不会改变对外业务错误契约。</p>
 */
public final class ContractReviewExecutionFailedException extends BusinessException {

    /**
     * 使用原始执行异常创建统一的 F02 内部错误。
     *
     * @param cause 导致审查无法完成的原始异常
     */
    public ContractReviewExecutionFailedException(Throwable cause) {
        super(BusinessErrorCode.INTERNAL_ERROR, BusinessErrorCode.INTERNAL_ERROR.defaultMessage(), cause);
    }
}
