package com.fuquan.audit.integration.material;

import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxContainerGuard;
import com.fuquan.audit.common.material.application.port.MaterialContainerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/** Procurement-only memory bounds; reuse the existing archive validator. */
@Configuration(proxyBeanMethods = false)
public class MaterialParsingConfiguration {
    @Bean("procurementDocxGuard")
    MaterialContainerPort procurementDocxGuard() {
        var limits = new ProjectIntakeProperties(DataSize.ofMegabytes(500), DataSize.ofMegabytes(50),
                DataSize.ofMegabytes(500), 200, 10, 100, 1024);
        return new ProjectDocxContainerGuard(new SelectedProjectArchiveReader(limits), limits)::validate;
    }
}
