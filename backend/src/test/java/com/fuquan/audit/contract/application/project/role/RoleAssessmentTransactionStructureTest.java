package com.fuquan.audit.contract.application.project.role;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class RoleAssessmentTransactionStructureTest {
    @Test
    void eachWritePhaseHasItsOwnRequiresNewTransactionAndComputeServiceHasNone() throws Exception {
        for (String methodName : new String[]{"start", "markRunning", "complete", "fail", "reconcileCompleteFailure"}) {
            Method method = java.util.Arrays.stream(RoleAssessmentTransactionCoordinator.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName)).findFirst().orElseThrow();
            Transactional annotation = method.getAnnotation(Transactional.class);
            assertThat(annotation).as(methodName).isNotNull();
            assertThat(annotation.propagation()).as(methodName).isEqualTo(Propagation.REQUIRES_NEW);
            assertThat(annotation.readOnly()).as(methodName).isFalse();
        }
        assertThat(RoleAssessmentService.class.getDeclaredMethod("assess", RoleAssessmentCommand.class)
                .getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void reopenIsAnIndependentReadOnlyTransaction() {
        Method method = java.util.Arrays.stream(RoleAssessmentTransactionCoordinator.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("reopen")).findFirst().orElseThrow();
        Transactional annotation = method.getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(annotation.readOnly()).isTrue();
    }

    @Test
    void completeBuildsItsResultWithoutSelfInvokingTransactionalReopen() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/fuquan/audit/contract/"
                + "application/project/role/RoleAssessmentTransactionCoordinator.java"));
        int completeStart = source.indexOf("public RoleAssessmentResult complete(");
        int nextTransaction = source.indexOf("@Transactional", completeStart + 1);
        assertThat(source.substring(completeStart, nextTransaction)).doesNotContain("reopen(");
    }
}
