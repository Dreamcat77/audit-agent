package com.fuquan.audit.contract.application.port;

/** 显式 CAS 或唯一 Slot 约束冲突。 */
public class ContractReviewConcurrencyException extends RuntimeException {
    public ContractReviewConcurrencyException(String message) {
        super(message);
    }

    public ContractReviewConcurrencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
