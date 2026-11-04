package com.fuquan.audit.common.application.port;

public class IdempotencyConcurrencyException extends RuntimeException {

    public IdempotencyConcurrencyException(String message) {
        super(message);
    }
}
