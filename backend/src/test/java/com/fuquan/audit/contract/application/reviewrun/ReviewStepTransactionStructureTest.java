package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class ReviewStepTransactionStructureTest {
    @Test
    void lifecycleWritesAreShortTransactionsAndFailureUnknownRequestedUseIndependentTransactions() {
        assertTransaction(ReviewStepStartTransactionCoordinator.class, "start", Propagation.REQUIRED);
        assertTransaction(ReviewStepCompleteTransactionCoordinator.class, "complete", Propagation.REQUIRED);
        assertTransaction(ReviewStepFailureTransactionCoordinator.class, "fail", Propagation.REQUIRES_NEW);
        assertTransaction(ReviewStepUnknownTransactionCoordinator.class, "unknown", Propagation.REQUIRES_NEW);
        assertTransaction(ReviewStepExternalRequestTransactionCoordinator.class,
                "markRequested", Propagation.REQUIRES_NEW);
    }

    @Test
    void aggregateWritersAcquireTheDurableRunRowLockBeforePublishingTerminalState() throws Exception {
        for (String source : new String[] {
                "ReviewStepStartTransactionCoordinator.java",
                "ReviewStepCompleteTransactionCoordinator.java",
                "ReviewStepFailureTransactionCoordinator.java",
                "ReviewStepUnknownTransactionCoordinator.java"}) {
            String java = Files.readString(Path.of("src", "main", "java", "com", "fuquan", "audit",
                    "contract", "application", "reviewrun", source));
            assertThat(java).as(source).contains("runs.findByIdForUpdate(");
        }
        String mapper = Files.readString(Path.of("src", "main", "resources", "mappers", "dm8",
                "ContractReviewRunMapper.xml")).replaceAll("\\s+", " ").toUpperCase();
        assertThat(mapper).contains("SELECT", "FROM CONTRACT_REVIEW_RUN", "FOR UPDATE");
    }

    private static void assertTransaction(Class<?> type, String methodName, Propagation propagation) {
        Method method = java.util.Arrays.stream(type.getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        Transactional annotation = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
        assertThat(annotation).as(type.getSimpleName() + "." + methodName).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(propagation);
    }
}
