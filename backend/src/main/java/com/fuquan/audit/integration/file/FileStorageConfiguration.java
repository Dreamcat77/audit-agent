package com.fuquan.audit.integration.file;

import com.fuquan.audit.contract.application.ReviewFilePolicy;
import com.fuquan.audit.contract.application.project.ProjectFilePolicy;
import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Maps typed infrastructure configuration into the Application-owned file policy. */
@Configuration(proxyBeanMethods = false)
public class FileStorageConfiguration {

    @Bean
    ReviewFilePolicy reviewFilePolicy(FileStorageProperties properties) {
        return new ReviewFilePolicy(
                properties.maxFileSizeBytes(), properties.getPendingRecoveryAge());
    }

    @Bean
    ProjectFilePolicy projectFilePolicy(
            ProjectIntakeProperties projectProperties, FileStorageProperties storageProperties) {
        return new ProjectFilePolicy(projectProperties, storageProperties.getPendingRecoveryAge());
    }
}
