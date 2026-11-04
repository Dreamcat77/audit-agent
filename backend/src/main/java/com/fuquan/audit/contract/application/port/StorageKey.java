package com.fuquan.audit.contract.application.port;

import java.util.Objects;
import java.util.regex.Pattern;

/** 不包含真实目录信息的服务端对象键。 */
public record StorageKey(String value) {
    private static final Pattern SAFE_KEY = Pattern.compile("^[a-f0-9]{64}$");

    public StorageKey {
        Objects.requireNonNull(value, "value must not be null");
        if (!SAFE_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("storage key must be lowercase SHA-256 hex");
        }
    }
}
