package com.fuquan.audit.contract.application.project;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("audit.project-intake")
public record ProjectIntakeProperties(
        DataSize maxUploadSize,
        DataSize maxEntrySize,
        DataSize maxExpandedArchiveSize,
        int maxEntries,
        int maxDepth,
        double maxCompressionRatio,
        int maxRelativePathLength) {

    public ProjectIntakeProperties {
        maxUploadSize = maxUploadSize == null ? DataSize.ofMegabytes(100) : maxUploadSize;
        maxEntrySize = maxEntrySize == null ? DataSize.ofMegabytes(50) : maxEntrySize;
        maxExpandedArchiveSize = maxExpandedArchiveSize == null ? DataSize.ofMegabytes(500) : maxExpandedArchiveSize;
        maxEntries = maxEntries == 0 ? 200 : maxEntries;
        maxDepth = maxDepth == 0 ? 10 : maxDepth;
        maxCompressionRatio = maxCompressionRatio == 0 ? 100 : maxCompressionRatio;
        maxRelativePathLength = maxRelativePathLength == 0 ? 1024 : maxRelativePathLength;
        if (maxUploadSize.toBytes() <= 0 || maxEntrySize.toBytes() <= 0
                || maxExpandedArchiveSize.toBytes() <= 0 || maxEntries < 1 || maxDepth < 1
                || maxCompressionRatio <= 1 || maxRelativePathLength < 1) {
            throw new IllegalArgumentException("project intake limits must be positive");
        }
    }

    public static ProjectIntakeProperties defaults() {
        return new ProjectIntakeProperties(null, null, null, 0, 0, 0, 0);
    }
}
