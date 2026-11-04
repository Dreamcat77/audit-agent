package com.fuquan.audit.common.application.port;

import java.util.Objects;

public record IdempotencyClaim(boolean acquired, IdempotencyRecord record) {

    public IdempotencyClaim {
        Objects.requireNonNull(record, "record must not be null");
    }
}
