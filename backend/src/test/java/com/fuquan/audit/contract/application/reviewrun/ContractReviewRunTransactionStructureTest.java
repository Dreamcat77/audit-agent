package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContractReviewRunTransactionStructureTest {
    @Test
    void localSerializationOccursBeforeTransactionalCoordinatorStartsDbWriteTx() throws Exception {
        String coordinator = Files.readString(Path.of("src", "main", "java", "com", "fuquan", "audit",
                "contract", "application", "reviewrun", "ContractReviewRunCreateTransactionCoordinator.java"));
        String service = Files.readString(Path.of("src", "main", "java", "com", "fuquan", "audit",
                "contract", "application", "reviewrun", "ContractReviewRunService.java"));
        assertThat(coordinator).contains("@Transactional")
                .doesNotContain("synchronized ContractReviewRunCreateResult create");
        assertThat(service).contains("synchronized ContractReviewRunCreateResult create");
    }

}
