package com.fuquan.audit.procurement.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** F11 投标文件上传边界。 */
@ConfigurationProperties("audit.procurement.collusion")
public record ProcurementCollusionProperties(DataSize maxUploadSize) {

    public ProcurementCollusionProperties {
        maxUploadSize = maxUploadSize == null ? DataSize.ofMegabytes(50) : maxUploadSize;
        if (maxUploadSize.toBytes() <= 0) {
            throw new IllegalArgumentException("procurement collusion max upload size must be positive");
        }
    }

    public static ProcurementCollusionProperties defaults() {
        return new ProcurementCollusionProperties(null);
    }
}
