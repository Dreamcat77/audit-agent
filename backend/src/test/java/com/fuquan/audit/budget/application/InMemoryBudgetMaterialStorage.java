package com.fuquan.audit.budget.application;

import com.fuquan.audit.budget.application.port.BudgetMaterialStoragePort;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BudgetReviewService 单测使用的存储替身。
 */
final class InMemoryBudgetMaterialStorage implements BudgetMaterialStoragePort {

    private final Map<String, byte[]> files = new ConcurrentHashMap<>();

    @Override
    public StoredMaterial store(InputStream inputStream, String fileName, long sizeLimit)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        inputStream.transferTo(output);
        if (output.size() == 0 || output.size() > sizeLimit) {
            throw new IOException("invalid test material size");
        }
        String reference = UUID.randomUUID().toString();
        files.put(reference, output.toByteArray());
        return new StoredMaterial(reference);
    }

    @Override
    public InputStream open(String reference) throws IOException {
        byte[] content = files.get(reference);
        if (content == null) {
            throw new IOException("test material is missing");
        }
        return new ByteArrayInputStream(content);
    }

    @Override
    public boolean exists(String reference) {
        return reference != null && files.containsKey(reference);
    }
}
