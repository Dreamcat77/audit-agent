package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fuquan.audit.common.observability.*;
import com.fuquan.audit.contract.application.*;
import com.fuquan.audit.contract.application.project.port.*;
import com.fuquan.audit.contract.application.reviewrun.executor.*;
import com.fuquan.audit.contract.application.reviewrun.port.*;
import com.fuquan.audit.contract.domain.*;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.contract.domain.reviewrun.*;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding;
import com.fuquan.audit.contract.domain.rule.AwardSupplierMatchRule;
import com.fuquan.audit.contract.infra.memory.LocalContractFixtures;
import com.fuquan.audit.contract.infra.memory.reviewrun.InMemoryContractReviewResultsRepositories;
import com.fuquan.audit.review.domain.RiskLevel;
import com.fuquan.audit.review.infra.memory.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real rules/projector/grounder/publication/completion and in-memory durable result stores. */
class F02SupplierCompletionTransactionTest {
    private static final Instant NOW = ReviewWorkerTransactionCoordinatorTest.NOW;

    @Test void normalCompletionPersistsAllThreeRealSupplierWitnesses() {
        var f = fixture();
        var started = f.worker.claim("supplier-worker-a");
        var computed = f.compute(started);
        assertCompleteWitnesses(computed);

        f.worker.coordinator.complete(started, computed);

        assertThat(f.worker.attempt.get().status()).isEqualTo(ContractReviewStepStatus.COMPLETED);
        assertThat(f.worker.step.get().status()).isEqualTo(ContractReviewStepStatus.COMPLETED);
        assertThat(f.results.findEvidenceByAttemptId(started.attempt().attemptId()))
                .containsExactlyElementsOf(computed.evidence());
        assertThat(f.tasks.findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE)).hasSize(1);
    }

    @Test void interruptedCompletionReopensSameTaskAndPersistsTheSameThreeWitnessIdentities() {
        var f = fixture();
        var started = f.worker.claim("supplier-worker-a");
        var computed = f.compute(started);
        assertCompleteWitnesses(computed);
        f.worker.now.set(NOW.plusSeconds(60));

        assertThat(f.worker.coordinator.recover(f.worker.currentCandidate(), "supplier-worker-b")).isEmpty();

        assertThat(f.worker.attempt.get().status()).isEqualTo(ContractReviewStepStatus.COMPLETED);
        assertThat(f.worker.attempt.get().outputResourceId()).isEqualTo(computed.outputResourceId());
        assertThat(f.results.findEvidenceByAttemptId(started.attempt().attemptId()))
                .extracting(ContractReviewEvidence::evidenceId)
                .containsExactlyElementsOf(computed.evidence().stream().map(ContractReviewEvidence::evidenceId).toList());
        assertThat(f.tasks.findAllLatest("CONTRACT", ContractReviewService.REVIEW_TYPE)).hasSize(1);
    }

    @Test void directCompletionCannotInjectSupplierV3EvidenceIntoAnotherRule() {
        var f = fixture();
        var started = f.worker.claim("supplier-worker-a");
        var computed = f.compute(started);
        var valid = computed.findings().get(0);
        var forged = new ContractReviewFinding(valid.findingId(), valid.runId(), valid.stepId(), valid.attemptId(),
                valid.ordinal(), valid.findingType(), F02RuleCodes.AWARD_AMOUNT_MATCH, valid.statusCode(), null,
                valid.riskLevel(), valid.summary(), valid.recommendation(), valid.findingSchemaVersion(),
                valid.policyVersion(), valid.source(), null, null, null, null, null, valid.createdAt(), valid.versionNo());
        var result = new StepComputationResult(computed.outputResourceType(), computed.outputResourceId(),
                List.of(forged), computed.evidence(), null, null, null);
        assertThatThrownBy(() -> f.completes.complete(started, result))
                .isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        assertThat(f.results.findFindingsByAttemptId(started.attempt().attemptId())).isEmpty();
        assertThat(f.results.findEvidenceByAttemptId(started.attempt().attemptId())).isEmpty();
        assertThat(f.worker.attempt.get().status()).isEqualTo(ContractReviewStepStatus.RUNNING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WRONG_ROLE", "MISSING_AWARD", "MIXED_POLICY", "OLD_STEP_NAMESPACE", "CREDIT_CODE_MODE", "LOCATOR_SCHEMA"})
    void directCompletionRejectsIncompleteOrMisScopedSupplierCertificates(String mutation) {
        var f = fixture();
        var started = f.worker.claim("supplier-worker-a");
        var computed = f.compute(started);
        var altered = computed;
        var context = started;
        if (mutation.equals("WRONG_ROLE")) {
            when(f.materialRepository.findMaterialsByRunId(started.run().runId())).thenReturn(
                    f.selected.stream().map(m -> copyRecord(m, "effectiveRole", DocumentRole.CONTRACT_BODY)).toList());
        } else if (mutation.equals("MISSING_AWARD")) {
            altered = new StepComputationResult(computed.outputResourceType(), computed.outputResourceId(),
                    computed.findings(), computed.evidence().subList(0, 2), null, null, null);
        } else if (mutation.equals("MIXED_POLICY") || mutation.equals("LOCATOR_SCHEMA")) {
            var proofs = new ArrayList<>(computed.evidence());
            proofs.set(0, mutation.equals("MIXED_POLICY")
                    ? copyRecord(proofs.get(0), "validationPolicyVersion", "f02-frozen-evidence-v2")
                    : copyRecord(proofs.get(0), "locatorSchemaVersion", 2));
            altered = new StepComputationResult(computed.outputResourceType(), computed.outputResourceId(),
                    computed.findings(), proofs, null, null, null);
        } else if (mutation.equals("OLD_STEP_NAMESPACE")) {
            String oldPolicy = "f02-policy-v1:" + "e".repeat(64);
            context = new ReviewStepStartTransactionCoordinator.StartedExecution(started.run(),
                    copyRecord(started.step(), "stepPolicyVersion", oldPolicy), started.attempt(),
                    started.idempotency(), started.actor(), started.command(), started.f02Configurations());
            altered = new StepComputationResult(computed.outputResourceType(), computed.outputResourceId(),
                    computed.findings().stream().map(value -> copyRecord(value, "policyVersion", oldPolicy)).toList(),
                    computed.evidence(), null, null, null);
        } else {
            var codeContract = LocalContractFixtures.contracts().get(0);
            var snapshot = new ContractSnapshotCanonicalizer().canonicalize(codeContract);
            var run = copyRecord(copyRecord(started.run(), "contractSnapshotCanonicalJson", snapshot.canonicalJson()),
                    "contractSnapshotSha256", snapshot.sha256());
            context = new ReviewStepStartTransactionCoordinator.StartedExecution(run, started.step(), started.attempt(),
                    started.idempotency(), started.actor(), started.command(), started.f02Configurations());
        }
        var rejectedContext = context;
        var rejectedResult = altered;
        assertThatThrownBy(() -> f.completes.complete(rejectedContext, rejectedResult))
                .as(mutation).isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
        assertThat(f.results.findFindingsByAttemptId(started.attempt().attemptId())).isEmpty();
        assertThat(f.results.findEvidenceByAttemptId(started.attempt().attemptId())).isEmpty();
    }

    private static void assertCompleteWitnesses(StepComputationResult result) {
        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.category()).isEqualTo(F02RuleCodes.AWARD_SUPPLIER_MATCH);
            assertThat(f.statusCode()).isEqualTo("PASS");
            assertThat(f.attentionCode()).isNull();
        });
        assertThat(result.evidence()).hasSize(3).allSatisfy(e -> {
            assertThat(e.validationPolicyVersion()).isEqualTo("f02-frozen-evidence-v3");
            assertThat(e.locatorAvailable()).isTrue();
        });
    }

    private static Fixture fixture() {
        var w = new ReviewWorkerTransactionCoordinatorTest.Fixture(ContractReviewStepType.F02_DETERMINISTIC);
        var oldContract = LocalContractFixtures.contracts().get(0);
        var oldAward = oldContract.awardNotice();
        var award = new AwardNoticeReference(oldAward.id(), oldAward.contractId(), oldAward.noticeNo(),
                oldAward.projectName(), oldContract.partyB(), null, oldAward.winningAmount(), oldAward.winningDate(), oldAward.sourceRef());
        var contract = new Contract(oldContract.id(), oldContract.contractNo(), oldContract.contractName(), oldContract.contractType(),
                oldContract.partyA(), oldContract.partyB(), null, oldContract.contractAmount(), oldContract.amountInWords(),
                oldContract.status(), oldContract.versionNo(), oldContract.createdAt(), oldContract.updatedAt(), oldContract.lineItems(), award);
        var configurations = List.of(new ContractRuleConfiguration(F02RuleCodes.AWARD_SUPPLIER_MATCH,
                "supplier", true, RiskLevel.YELLOW, false, 9, "test", NOW));
        var policies = new ContractReviewExecutionPolicyResolver(() -> configurations, new F02RulePolicyFingerprint());
        String policy = policies.resolveCurrentF02().ruleVersion();
        var canonical = new ContractSnapshotCanonicalizer().canonicalize(contract);
        var old = w.run.get();
        w.run.set(new ContractReviewRun(old.runId(), old.projectId(), contract.id(), contract.versionNo(),
                UUID.randomUUID(), null, 2, canonical.canonicalJson(), canonical.sha256(), old.reviewProfileVersion(),
                policy, old.modelPolicyVersion(), old.inputSnapshotIdentity(), old.requestedBy(), old.status(),
                old.requestedAt(), old.startedAt(), old.completedAt(), old.failedAt(), old.unknownAt(), old.safeReasonCode(), old.versionNo()));
        var step = w.step.get();
        w.step.set(new ContractReviewStep(step.stepId(), step.runId(), step.stepType(), step.required(), step.status(),
                policy, step.currentAttemptNo(), null, null, null, null, null, step.versionNo()));
        String heading = "Party: " + contract.partyB(), signature = "Signed by: " + contract.partyB();
        var body = material(1, DocumentRole.CONTRACT_BODY, heading + "\n" + signature);
        var notice = material(2, DocumentRole.AWARD_NOTICE, "Winner: " + contract.partyB());
        List<FrozenReviewMaterial> frozen = List.of(body, notice);
        List<ContractReviewRunMaterial> selected = frozen.stream().map(m -> new ContractReviewRunMaterial(m.runMaterialId(),
                old.runId(), m.ordinal(), old.projectId(), m.fileId(), m.fileSha256(), m.parseResultId(), m.parseVersion(),
                m.canonicalTextSha256(), m.effectiveRole(), m.roleDecisionId(), m.identityDecisionId(), null, old.requestedBy(), NOW)).toList();
        when(w.inputs.load(any(), any(), any())).thenAnswer(call -> new FrozenReviewInput(w.run.get(), contract, frozen));
        var extraction = mock(ExtractionResultRepository.class);
        when(extraction.findBySourceFileIdAndParseResultId(old.projectId(), body.fileId(), body.parseResultId()))
                .thenReturn(List.of(fact(w.run.get(), body, "PARTY_B", contract.partyB(), heading, 1),
                        fact(w.run.get(), body, "PARTY_B", contract.partyB(), signature, 2)));
        when(extraction.findBySourceFileIdAndParseResultId(old.projectId(), notice.fileId(), notice.parseResultId()))
                .thenReturn(List.of(fact(w.run.get(), notice, "AWARD_SUPPLIER", contract.partyB(), notice.canonicalText(), 1)));
        var source = new FindingSourceProjectionService(mock(ProjectContractQueryPort.class),
                mock(ContractCandidateRepository.class), mock(ProjectFileRepository.class), extraction);
        var mapper = new FrozenF02ResultMapper(source, new FrozenEvidenceGrounder(), Clock.fixed(NOW, ZoneOffset.UTC));
        var materials = mock(ContractReviewRunMaterialRepository.class);
        when(materials.findMaterialsByRunId(old.runId())).thenReturn(selected);
        var results = new InMemoryContractReviewResultsRepositories(w.attempts, materials, w.steps);
        var tasks = new InMemoryAuditTaskRepository();
        var checks = new InMemoryAuditCheckResultRepository();
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return w.now.get(); }
        };
        var completes = new ReviewStepCompleteTransactionCoordinator(w.runs, w.steps, w.attempts, materials,
                results, results, w.idempotency, w.events::add, new ReviewRunStatusAggregator(), clock);
        var runService = mock(ContractReviewRunService.class);
        when(runService.get(old.projectId(), contract.id(), old.runId())).thenAnswer(call -> new ContractReviewRunCreateResult(
                w.run.get(), selected, List.of(w.step.get()), List.of(w.attempt.get()), false));
        var outputs = new ReviewStepOutputReadService(runService, results, results, tasks, checks, w.idempotency);
        w.coordinator = new ReviewWorkerTransactionCoordinator(w.workers, w.runs, w.steps, w.attempts,
                w.idempotency, w.events::add, policies, w.inputs, completes, outputs, new ReviewRunStatusAggregator(),
                clock, Duration.ofSeconds(60), mapper);
        var flow = new FlowDebugLogger(new ObservabilityProperties());
        var reviews = new ContractReviewService(id -> { throw new AssertionError("no current contract"); },
                () -> { throw new AssertionError("no current configurations"); },
                new ContractReviewTransactionCoordinator(checks, tasks, w.idempotency, w.events::add, flow),
                Optional::empty, clock, flow, List.of(new AwardSupplierMatchRule()));
        return new Fixture(w, new FrozenF02ReviewStepExecutor(reviews, mapper), contract, frozen, results, tasks, completes,
                materials, selected);
    }

    private static FrozenReviewMaterial material(int ordinal, DocumentRole role, String text) {
        return new FrozenReviewMaterial(UUID.randomUUID(), ordinal, UUID.randomUUID(), "b".repeat(64), UUID.randomUUID(),
                1, ContractSnapshotCanonicalizer.sha256(text), text, role, UUID.randomUUID(), UUID.randomUUID(), null, "synthetic.txt");
    }

    private static ExtractionResult fact(ContractReviewRun run, FrozenReviewMaterial m, String type,
            String value, String excerpt, int paragraph) {
        return new ExtractionResult(UUID.randomUUID(), run.projectId(), m.fileId(), m.parseResultId(),
                m.effectiveRole() == DocumentRole.CONTRACT_BODY ? run.sourceCandidateId() : null,
                ContractSnapshotCanonicalizer.sha256(type + excerpt), "test-v1", type, null, value, value, null,
                excerpt, null, paragraph, "docx:paragraph:" + paragraph, NOW.minusSeconds(1));
    }

    private record Fixture(ReviewWorkerTransactionCoordinatorTest.Fixture worker, FrozenF02ReviewStepExecutor executor,
            Contract contract, List<FrozenReviewMaterial> materials, InMemoryContractReviewResultsRepositories results,
            InMemoryAuditTaskRepository tasks, ReviewStepCompleteTransactionCoordinator completes,
            ContractReviewRunMaterialRepository materialRepository, List<ContractReviewRunMaterial> selected) {
        StepComputationResult compute(ReviewStepStartTransactionCoordinator.StartedExecution started) {
            return executor.execute(new FrozenReviewInput(started.run(), contract, materials),
                    new ReviewExecutionContext(started.run(), started.step(), started.attempt(), started.actor(),
                            started.command().metadata(), started.f02Configurations(), value -> { }));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Record> T copyRecord(T source, String field, Object replacement) {
        try {
            var fields = source.getClass().getRecordComponents();
            Class<?>[] types = new Class<?>[fields.length];
            Object[] values = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) {
                types[i] = fields[i].getType();
                values[i] = fields[i].getName().equals(field) ? replacement : fields[i].getAccessor().invoke(source);
            }
            return (T) source.getClass().getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
