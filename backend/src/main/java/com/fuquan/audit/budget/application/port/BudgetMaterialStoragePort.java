package com.fuquan.audit.budget.application.port;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * F01 预算材料存储出站 Port。
 *
 * <p>引用是 opaque 字符串，Application 不接触 Path、Files 或具体存储介质。</p>
 */
public interface BudgetMaterialStoragePort {

    StoredMaterial store(InputStream inputStream, String fileName, long sizeLimit) throws IOException;

    InputStream open(String reference) throws IOException;

    boolean exists(String reference);
    default void delete(String reference) throws IOException {throw new UnsupportedOperationException("material cleanup is not supported");}

    record StoredMaterial(String reference) {
        public StoredMaterial {
            Objects.requireNonNull(reference, "reference must not be null");
            if (reference.isBlank()) {
                throw new IllegalArgumentException("reference must not be blank");
            }
        }
    }
}
