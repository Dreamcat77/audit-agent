package com.fuquan.audit.contract.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class ContractReviewSessionTransactionStructureTest {
    @Test
    void uploadStartCompleteFailureAndRecoveryReadsUseIndependentTransactions() {
        assertRequiresNew("startUpload");
        assertRequiresNew("completeUpload");
        assertRequiresNew("failUpload");
        assertRequiresNew("readIdempotency");
        assertRequiresNew("readUploadIdempotency");
        assertRequiresNew("deleteSession");
    }

    @Test
    void outerApplicationUploadDoesNotHoldATransaction() throws Exception {
        Method upload = ContractReviewSessionService.class.getMethod(
                "upload", UploadReviewDocumentCommand.class);
        assertThat(upload.getAnnotation(Transactional.class)).isNull();
        Method delete = ContractReviewSessionService.class.getMethod(
                "delete", DeleteReviewSessionCommand.class);
        assertThat(delete.getAnnotation(Transactional.class)).isNull();
    }

    private void assertRequiresNew(String name) {
        Method method = Arrays.stream(ContractReviewSessionTransactionCoordinator.class.getMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst().orElseThrow();
        Transactional annotation = method.getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
