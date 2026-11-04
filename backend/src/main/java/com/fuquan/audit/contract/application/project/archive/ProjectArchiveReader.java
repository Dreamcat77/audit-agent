package com.fuquan.audit.contract.application.project.archive;

import java.io.InputStream;
import java.util.Objects;

public interface ProjectArchiveReader {
    void read(InputStream input, EntryConsumer consumer);

    @FunctionalInterface
    interface EntryConsumer {
        void accept(Entry entry);
    }

    /** Entry content is valid only for the duration of the consumer callback. */
    record Entry(String normalizedPath, InputStream content, long declaredSize, long compressedSize, long crc) {
        public Entry {
            Objects.requireNonNull(normalizedPath);
            Objects.requireNonNull(content);
        }
    }
}
