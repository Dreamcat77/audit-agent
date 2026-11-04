package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractFinalCloseoutMapperRowVisibilityTest {
    @Test
    void mapperExposedRowsArePublicForJdkProxyAccess() {
        for (Class<?> rowType : List.of(ContractFactExtractionAttemptRow.class,
                ContractFactExtractionCandidateRow.class,
                ContractFactExtractionRejectionRow.class,
                ContractModelCallAuditRow.class,
                ContractReviewClauseComparisonPersistenceRow.class,
                ContractReviewClauseValuePersistenceRow.class)) {
            assertThat(Modifier.isPublic(rowType.getModifiers()))
                    .as("%s must be accessible from the jdk.proxy2 module", rowType.getSimpleName())
                    .isTrue();
        }
    }
}
