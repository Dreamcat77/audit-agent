package com.fuquan.audit.integration.dm8;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.reviewrun.ReviewWorkerLeasePolicy;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewWorkerRepository.LeaseCondition;
import com.fuquan.audit.contract.domain.reviewrun.*;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class ReviewWorkerRepositoryTest {
    final Instant now = Instant.parse("2026-09-16T01:00:00Z");

    @Test void rejectsLostCasInsteadOfReturningAClaim() {
        var mapper = mock(ContractReviewWorkerMapper.class);
        var repository = new Dm8ContractReviewWorkerRepository(mapper);
        var pending = pending();
        var next = new ReviewWorkerLeasePolicy().claim(pending, "worker-a", now, now.plusSeconds(60));
        assertThatThrownBy(() -> repository.compareAndSetAttempt(pending, next, LeaseCondition.PENDING, now))
                .isInstanceOf(BusinessException.class);
    }

    @Test void boundedQueriesRejectZeroAndOversizedLimitBeforeDatabase() {
        var repository = new Dm8ContractReviewWorkerRepository(mock(ContractReviewWorkerMapper.class));
        for (int limit : new int[] {0, -1, 101}) {
            assertThatThrownBy(() -> repository.findClaimCandidates(limit, now))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.findExpiredLeases(limit, now))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void activeUpdateBindsCallerVersionOwnerAndLiveLeaseAndCurrentAttempt() throws Exception {
        Configuration configuration = configuration();
        var pending = row(pending());
        var params = Map.of("expected", pending, "next", pending, "condition", "ACTIVE",
                "now", LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        var bound = configuration.getMappedStatement(namespace("compareAndSetAttempt")).getBoundSql(params);
        String sql = bound.getSql().replaceAll("\\s+", " ").toUpperCase();
        assertThat(sql).contains("VERSION_NO = ?", "LEASE_OWNER = ?", "LEASE_UNTIL > ?",
                "CURRENT_ATTEMPT_NO", "ATTEMPT_ID = ?", "STATUS = ?", "EXISTS");
        assertThat(bound.getParameterMappings()).extracting(value -> value.getProperty())
                .contains("expected.versionNo", "expected.leaseOwner", "expected.attemptId",
                        "expected.status", "expected.attemptNo", "now");
    }

    @Test void pendingAndExpiredSqlHaveDistinctLeasePredicates() throws Exception {
        var configuration = configuration();
        var row = row(pending());
        String pending = configuration.getMappedStatement(namespace("compareAndSetAttempt"))
                .getBoundSql(Map.of("expected", row, "next", row, "condition", "PENDING", "now", now))
                .getSql().replaceAll("\\s+", " ").toUpperCase();
        String expired = configuration.getMappedStatement(namespace("compareAndSetAttempt"))
                .getBoundSql(Map.of("expected", row, "next", row, "condition", "EXPIRED", "now", now))
                .getSql().replaceAll("\\s+", " ").toUpperCase();
        assertThat(pending).contains("LEASE_OWNER IS NULL", "LEASE_UNTIL IS NULL");
        assertThat(expired).contains("LEASE_UNTIL <= ?", "LEASE_OWNER = ?");
    }

    @Test void discoveryLimitsDatabaseRowsAndUsesDurableCurrentAttempt() throws Exception {
        var configuration = configuration();
        for (String method : new String[] {"findClaimCandidates", "findExpiredLeases"}) {
            var bound = configuration.getMappedStatement(namespace(method))
                    .getBoundSql(Map.of("limit", 4, "now", now));
            assertThat(bound.getSql().replaceAll("\\s+", " ").toUpperCase())
                    .contains("ROWNUM <= ?", "CURRENT_ATTEMPT_NO", "ORDER BY", "CONTRACT_REVIEW_RUN");
            assertThat(bound.getParameterMappings()).extracting(value -> value.getProperty()).contains("limit");
        }
    }

    private static Configuration configuration() throws Exception {
        String resource = "mappers/dm8/ContractReviewWorkerMapper.xml";
        var configuration = new Configuration();
        try (InputStream input = ReviewWorkerRepositoryTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).as("worker SQL mapper").isNotNull();
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
    private static String namespace(String method) {
        return "com.fuquan.audit.integration.dm8.ContractReviewWorkerMapper." + method;
    }
    private static ContractReviewStepAttempt pending() {
        return new ContractReviewStepAttempt(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "a".repeat(64), ContractReviewStepStatus.PENDING, null, null, null,
                ReviewStepExternalCallState.NOT_STARTED, null, null, null, null, null, null, null, null, null, 0);
    }
    private static ContractReviewStepAttemptPersistenceRow row(ContractReviewStepAttempt value) {
        return new ContractReviewStepAttemptPersistenceRow(value.attemptId().toString(), value.runId().toString(),
                value.stepId().toString(), 1, value.operationIdentity(), "PENDING", null, null, null,
                "NOT_STARTED", null, null, null, null, null, null, null, null, null, 0);
    }
}
