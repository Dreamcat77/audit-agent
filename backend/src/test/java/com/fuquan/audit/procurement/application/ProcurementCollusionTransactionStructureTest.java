package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class ProcurementCollusionTransactionStructureTest {

    @Test
    void expensiveScoringRunsOutsideTransactionAndOnlyCommitCoordinatorIsTransactional() throws Exception {
        Method score = ProcurementCollusionReviewService.class.getMethod("score", UUID.class);
        Method saveScore = ProcurementCollusionTransactionCoordinator.class.getMethod("saveScore", UUID.class,
                com.fuquan.audit.procurement.domain.ProcurementCollusionAssessment.class,
                com.fuquan.audit.procurement.domain.ProcurementSplitPurchaseAssessment.class, String.class);

        assertThat(score.getAnnotation(Transactional.class)).isNull();
        assertThat(saveScore.getAnnotation(Transactional.class)).isNotNull();
    }
}
