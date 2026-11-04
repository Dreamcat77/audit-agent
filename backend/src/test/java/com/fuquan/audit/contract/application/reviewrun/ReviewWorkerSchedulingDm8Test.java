package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewWorkerRepository;
import com.fuquan.audit.contract.domain.reviewrun.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Executes the shipped DM8 discovery SQL against SELECT-only synthetic CTEs, then the real worker. */
@EnabledIfSystemProperty(named = "audit.dm8.read-only-scheduling", matches = "true")
class ReviewWorkerSchedulingDm8Test {
    @Test void sameRunFastStepsCompleteBeforeLongSemanticStartsEvenWhenItsAttemptSortsFirst() throws Exception {
        var f = new ReviewWorkerTransactionCoordinatorTest.Fixture(ContractReviewStepType.BREACH);
        var base = f.claim("worker-scheduling-test");
        var types = List.of(ContractReviewStepType.SEMANTIC_AI, ContractReviewStepType.F02_DETERMINISTIC,
                ContractReviewStepType.BREACH, ContractReviewStepType.TERM);
        var starts = new HashMap<UUID, ReviewStepStartTransactionCoordinator.StartedExecution>();
        var candidates = new HashMap<String, ContractReviewWorkerRepository.Candidate>();
        for (int index = 0; index < types.size(); index++) {
            UUID stepId = new UUID(0, index + 11), attemptId = new UUID(0, index + 1);
            var step = new ContractReviewStep(stepId, base.run().runId(), types.get(index), true,
                    ContractReviewStepStatus.RUNNING, "policy", 1, null, null, null, base.run().requestedAt(), null, 1);
            var old = base.attempt();
            var attempt = new ContractReviewStepAttempt(attemptId, base.run().runId(), stepId, 1,
                    old.operationIdentity(), ContractReviewStepStatus.RUNNING, old.leaseOwner(), old.leaseUntil(),
                    old.claimedAt(), old.externalCallState(), null, null, null, null, old.startedAt(), null,
                    null, null, null, 1);
            starts.put(stepId, new ReviewStepStartTransactionCoordinator.StartedExecution(base.run(), step, attempt,
                    base.idempotency(), base.actor(), base.command(), base.f02Configurations()));
            candidates.put(stepId.toString(), new ContractReviewWorkerRepository.Candidate(base.run().runId(), stepId, attemptId, 0));
        }
        List<String> selected = discover(starts, 4);
        var repository = mock(ContractReviewWorkerRepository.class);
        when(repository.findExpiredLeases(anyInt(), any())).thenReturn(List.of());
        when(repository.findClaimCandidates(anyInt(), any())).thenReturn(selected.stream().map(candidates::get).toList());
        var transactions = mock(ReviewWorkerTransactionCoordinator.class);
        when(transactions.claim(any(), anyString())).thenAnswer(call -> starts.get(call.getArgument(0,
                ContractReviewWorkerRepository.Candidate.class).stepId()));
        when(transactions.heartbeat(any())).thenAnswer(call -> call.getArgument(0));
        var completed = new CopyOnWriteArrayList<ContractReviewStepType>();
        completed.add(ContractReviewStepType.MATERIAL_VALIDATION);
        doAnswer(call -> { completed.add(call.getArgument(0,
                ReviewStepStartTransactionCoordinator.StartedExecution.class).step().stepType()); return null; })
                .when(transactions).complete(any(), any());
        var semanticEntered = new CountDownLatch(1);
        var releaseSemantic = new CountDownLatch(1);
        var atSemanticStart = new CopyOnWriteArrayList<ContractReviewStepType>();
        List<ReviewStepExecutor> executors = types.stream().map(type -> (ReviewStepExecutor) new ReviewStepExecutor() {
            public ContractReviewStepType stepType() { return type; }
            public StepComputationResult execute(FrozenReviewInput input, ReviewExecutionContext context) {
                if (type == ContractReviewStepType.SEMANTIC_AI) {
                    atSemanticStart.addAll(completed);
                    semanticEntered.countDown();
                    try { releaseSemantic.await(); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                    }
                }
                return new StepComputationResult("REVIEW_FINDING_SET", context.startedAttempt().attemptId(),
                        List.of(), List.of(), null, null, null);
            }
        }).toList();
        var scan = Executors.newSingleThreadExecutor();
        try (var worker = new ContractReviewWorker(repository, transactions, f.inputs, executors,
                Clock.fixed(base.run().requestedAt(), ZoneOffset.UTC), "worker-scheduling-test",
                Duration.ofSeconds(1), Duration.ofSeconds(10))) {
            var running = scan.submit(() -> worker.scanOnce(4));
            try {
                assertThat(semanticEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(atSemanticStart).containsExactly(ContractReviewStepType.MATERIAL_VALIDATION,
                        ContractReviewStepType.F02_DETERMINISTIC, ContractReviewStepType.BREACH, ContractReviewStepType.TERM);
                assertThat(running.isDone()).as("semantic is still in flight while fast outputs are complete").isFalse();
            } finally { releaseSemantic.countDown(); }
            running.get(5, TimeUnit.SECONDS);
        } finally { releaseSemantic.countDown(); scan.shutdownNow(); }
        assertThat(discover(starts, 1)).as("priority must apply before the database limit")
                .containsExactly(new UUID(0, 12).toString());
    }

    private static List<String> discover(Map<UUID, ReviewStepStartTransactionCoordinator.StartedExecution> starts,
            int limit) throws Exception {
        String resource = "mappers/dm8/ContractReviewWorkerMapper.xml";
        var configuration = new Configuration();
        try (var in = ReviewWorkerSchedulingDm8Test.class.getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        String sql = configuration.getMappedStatement("com.fuquan.audit.integration.dm8.ContractReviewWorkerMapper.findClaimCandidates")
                .getBoundSql(Map.of("limit", limit, "now", Instant.parse("2026-10-16T00:00:00Z"))).getSql();
        List<String> attempts = new ArrayList<>(), steps = new ArrayList<>();
        for (var started : starts.values()) {
            String run = started.run().runId().toString(), step = started.step().stepId().toString();
            attempts.add("SELECT '" + run + "' RUN_ID,'" + step + "' STEP_ID,'" + started.attempt().attemptId()
                    + "' ATTEMPT_ID,1 ATTEMPT_NO,'PENDING' STATUS,CAST(NULL AS VARCHAR(100)) LEASE_OWNER,"
                    + "CAST(NULL AS TIMESTAMP) LEASE_UNTIL,'NOT_STARTED' EXTERNAL_CALL_STATE FROM DUAL");
            steps.add("SELECT '" + run + "' RUN_ID,'" + step + "' STEP_ID,'" + started.step().stepType()
                    + "' STEP_TYPE,'PENDING' STATUS,1 CURRENT_ATTEMPT_NO FROM DUAL");
        }
        String fixture = "WITH CONTRACT_REVIEW_STEP_ATTEMPT AS (" + String.join(" UNION ALL ", attempts)
                + "),CONTRACT_REVIEW_STEP AS (" + String.join(" UNION ALL ", steps)
                + "),CONTRACT_REVIEW_RUN AS (SELECT '" + starts.values().iterator().next().run().runId()
                + "' RUN_ID,'RUNNING' STATUS FROM DUAL) ";
        try (var connection = DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),
                System.getenv("AUDIT_DB_USERNAME"), System.getenv("AUDIT_DB_PASSWORD"))) {
            connection.setReadOnly(true); connection.setAutoCommit(false);
            List<String> ids = new ArrayList<>();
            try (var statement = connection.prepareStatement(fixture + sql)) {
                statement.setQueryTimeout(10); statement.setInt(1, limit);
                try (var rows = statement.executeQuery()) { while (rows.next()) ids.add(rows.getString("STEP_ID")); }
            }
            connection.rollback();
            return ids;
        } catch (SQLException failure) {
            throw new AssertionError("Read-only DM8 scheduling check failed; vendorCode=" + failure.getErrorCode());
        }
    }
}
