package com.fuquan.audit.integration.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DataSizeUnit;
import org.springframework.util.unit.DataSize;
import org.springframework.util.unit.DataUnit;
import org.springframework.validation.annotation.Validated;

/** 类型安全的文件接入配置。 */
@Validated
@ConfigurationProperties(prefix = "audit.file-storage")
public class FileStorageProperties {

    @NotBlank
    private String root;

    @NotNull
    @DataSizeUnit(DataUnit.MEGABYTES)
    private DataSize maxFileSize = DataSize.ofMegabytes(50);

    @NotNull
    private Duration pendingRecoveryAge = Duration.ofMinutes(15);

    @NotNull
    private Duration stagingRetentionAge = Duration.ofHours(24);

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public Duration getPendingRecoveryAge() {
        return pendingRecoveryAge;
    }

    public void setPendingRecoveryAge(Duration pendingRecoveryAge) {
        this.pendingRecoveryAge = pendingRecoveryAge;
    }

    public Duration getStagingRetentionAge() {
        return stagingRetentionAge;
    }

    public void setStagingRetentionAge(Duration stagingRetentionAge) {
        this.stagingRetentionAge = stagingRetentionAge;
    }

    public long maxFileSizeBytes() {
        return maxFileSize.toBytes();
    }
}
