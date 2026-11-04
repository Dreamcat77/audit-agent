package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.budget.domain.BudgetReviewTask;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class BudgetReviewTransactionStructureTest {

    @Test
    void orchestrationMethodsDoNotOwnLongTransactions() throws Exception {
        assertThat(BudgetReviewService.class.getMethod("review", UUID.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(BudgetReviewService.class.getMethod(
                "confirmRow", UUID.class, int.class, String.class, String.class, String.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(BudgetReviewService.class.getMethod(
                "confirmException", UUID.class, int.class, String.class,
                String.class, String.class, String.class, String.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(Modifier.isSynchronized(BudgetReviewService.class.getMethod(
                "confirmRow", UUID.class, int.class, String.class, String.class, String.class)
                .getModifiers())).isFalse();
    }

    @Test
    void persistenceBoundariesAreExplicitShortTransactions() throws Exception {
        assertTransactional("loadReview", UUID.class);
        assertTransactional(
                "saveProgress", BudgetReviewTask.class, java.util.List.class);
        assertTransactional(
                "confirmAndPrepare", UUID.class, int.class, String.class,
                com.fuquan.audit.budget.domain.BudgetValidationConfirmLog.class,
                BudgetReviewTask.class);
        assertThat(Modifier.isSynchronized(BudgetReviewTransactionCoordinator.class.getMethod(
                "confirmAndPrepare", UUID.class, int.class, String.class,
                com.fuquan.audit.budget.domain.BudgetValidationConfirmLog.class,
                BudgetReviewTask.class).getModifiers())).isFalse();
        assertThat(BudgetReviewTransactionCoordinator.class.getMethod(
                "fail", BudgetReviewTask.class, String.class)
                .getAnnotation(Transactional.class).propagation())
                .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
    }

    private static void assertTransactional(String method, Class<?>... parameterTypes)
            throws Exception {
        Method reflected = BudgetReviewTransactionCoordinator.class.getMethod(
                method, parameterTypes);
        assertThat(reflected.isAnnotationPresent(Transactional.class)).isTrue();
    }
}
