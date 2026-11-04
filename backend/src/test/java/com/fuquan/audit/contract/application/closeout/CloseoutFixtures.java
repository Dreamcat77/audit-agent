package com.fuquan.audit.contract.application.closeout;

import static org.mockito.Mockito.*;
import com.fuquan.audit.common.application.port.*;
import com.fuquan.audit.contract.application.ContractReviewService;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.reviewrun.*;
import com.fuquan.audit.contract.application.reviewrun.executor.FrozenF02ReviewStepExecutor;
import com.fuquan.audit.contract.domain.*;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.contract.domain.reviewrun.*;
import com.fuquan.audit.contract.infra.memory.closeout.InMemoryContractReviewCloseoutRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectFileParseResultRepository;
import com.fuquan.audit.contract.infra.memory.reviewrun.*;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.review.application.port.*;
import com.fuquan.audit.review.domain.*;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Entirely synthetic durable repository fixtures; production readers remain real. */
class CloseoutFixtures {
    final UUID project = UUID.randomUUID(), contract = UUID.randomUUID(), runId = UUID.randomUUID();
    final Instant now = Instant.parse("2026-09-16T00:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final ProjectRequestMetadata metadata = new ProjectRequestMetadata("request", "trace", null);
    final InMemoryContractReviewRunRepositories runs = new InMemoryContractReviewRunRepositories();
    final InMemoryContractReviewResultsRepositories results = new InMemoryContractReviewResultsRepositories(runs, runs, runs);
    final InMemoryContractReviewCloseoutRepository closeout = new InMemoryContractReviewCloseoutRepository();
    final InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
    final InMemoryProjectFileParseResultRepository parses = new InMemoryProjectFileParseResultRepository();
    final AuditTaskRepository tasks = mock(AuditTaskRepository.class);
    final AuditCheckResultRepository checks = mock(AuditCheckResultRepository.class);
    final FindingAcknowledgementReadRepository f02Acks = mock(FindingAcknowledgementReadRepository.class);
    final AuthContext auth = mock(AuthContext.class);
    final List<OperationAuditEvent> audit = new java.util.concurrent.CopyOnWriteArrayList<>();
    final CurrentUser actor = new CurrentUser(UUID.randomUUID().toString(), "tester", "测试人员", Set.of("AUDITOR"));
    final ContractReviewRun run;
    final ContractReviewRunService runService;
    final ReviewStepOutputReadService outputs;
    final FrozenReviewInputLoader frozen;
    final com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding finding;
    final AuditCheckResult check;

    CloseoutFixtures() { this(ContractReviewRunStatus.COMPLETED); }
    CloseoutFixtures(ContractReviewRunStatus status) {
        when(auth.currentUser()).thenReturn(Optional.of(actor));
        var canonicalizer = new ContractSnapshotCanonicalizer();
        var structured = new Contract(contract, "SYNTHETIC-C1", "测试合同", "GOODS", "测试甲方", "测试乙方", null,
                Money.of(new BigDecimal("100.00")), null, "READY_FOR_REVIEW", 0, now, now, List.of(), null);
        var snapshot = canonicalizer.canonicalize(structured);
        UUID file = UUID.randomUUID(), parse = UUID.randomUUID();
        String text = "合成合同条款";
        var material = new ContractReviewRunMaterial(UUID.randomUUID(), runId, 1, project, file,
                "a".repeat(64), parse, 1, ContractSnapshotCanonicalizer.sha256(text), DocumentRole.CONTRACT_BODY,
                UUID.randomUUID(), UUID.randomUUID(), null, actor.userId(), now);
        String input = new ReviewRunSnapshotIdentityFactory().identity(project, contract, 0, snapshot.sha256(),
                null, null, List.of(material), "profile", "rule", "model");
        run = new ContractReviewRun(runId, project, contract, 0, null, null, 1, snapshot.canonicalJson(),
                snapshot.sha256(), "profile", "rule", "model", input, actor.userId(), status, now,
                status == ContractReviewRunStatus.PENDING ? null : now,
                status == ContractReviewRunStatus.COMPLETED ? now : null, null, null, null, 1);
        runs.insert(run); runs.insert(material);
        parses.insertIfAbsent(new ProjectFileParseResult(parse, project, file, "a".repeat(64), 1,
                ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED, ProjectFileParseMethod.PDF_TEXT,
                "PDFBox", "3", text, material.canonicalTextSha256(), 1, 1L, null, null, now, now, 0));
        var manifest = new ReviewRunManifestFactory().create(runId, now);
        com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding savedFinding = null;
        AuditCheckResult savedCheck = null;
        for (var step : manifest.steps()) {
            if (step.stepType() == ContractReviewStepType.MATERIAL_VALIDATION) { runs.insert(step); continue; }
            var old = manifest.attempts().stream().filter(a -> a.stepId().equals(step.stepId())).findFirst().orElseThrow();
            boolean f02 = step.stepType() == ContractReviewStepType.F02_DETERMINISTIC;
            UUID outputId = f02 ? UUID.randomUUID() : old.attemptId();
            String outputType = f02 ? "AUDIT_TASK" : "REVIEW_FINDING_SET";
            var completed = new ContractReviewStep(step.stepId(), runId, step.stepType(), true,
                    ContractReviewStepStatus.COMPLETED, step.stepPolicyVersion(), 1, outputType, outputId, null, now, now, 2);
            var attempt = new ContractReviewStepAttempt(old.attemptId(), runId, step.stepId(), 1, old.operationIdentity(),
                    ContractReviewStepStatus.COMPLETED, null, null, now, ReviewStepExternalCallState.NOT_STARTED,
                    null, null, null, null, now, now, null, outputType, outputId, 2);
            runs.insert(completed); runs.insert(attempt);
            if (f02) {
                var task = new AuditTask(outputId, "CONTRACT", contract, 0, "F02_DETERMINISTIC", AuditTaskStatus.DONE,
                        actor.userId(), "request", "trace", now, now, now, 2);
                savedCheck = new AuditCheckResult(UUID.randomUUID(), outputId, "F02_AMOUNT_WORDS_MATCH", null,
                        CheckStatus.FAIL, RiskLevel.RED, true, "100", "200", "金额表达不一致", 1, now);
                when(tasks.findById(outputId)).thenReturn(Optional.of(task));
                when(checks.findByTaskId(outputId)).thenReturn(List.of(savedCheck));
                when(f02Acks.findByTaskId(outputId)).thenReturn(List.of());
                var operation = idempotency.claim(new IdempotencyRequest(ContractReviewService.IDEMPOTENCY_SCOPE,
                        FrozenF02ReviewStepExecutor.internalKey(runId, step.stepId(), old.attemptId()), actor.userId(),
                        "CONTRACT", contract.toString(), "f".repeat(64), "AUDIT_TASK", outputId.toString(), "request", "trace", now));
                idempotency.markSucceeded(operation.record(), "AUDIT_TASK", outputId.toString(), now);
            } else if (step.stepType() == ContractReviewStepType.BREACH) {
                savedFinding = new com.fuquan.audit.contract.domain.reviewrun.ContractReviewFinding(UUID.randomUUID(), runId,
                        step.stepId(), old.attemptId(), 1, "BREACH", "CLAUSE", "NEEDS_REVIEW", null, null,
                        "请核对违约条款", "留下处理意见", 1, step.stepPolicyVersion(), ReviewFindingSource.DETERMINISTIC,
                        null, null, null, null, null, now, 0);
                results.insert(savedFinding);
                results.insert(new ContractReviewEvidence(UUID.randomUUID(), savedFinding.findingId(), runId, step.stepId(),
                        old.attemptId(), 1, file, material.fileSha256(), parse, 1, material.canonicalTextSha256(), "PROBLEM",
                        "VALIDATED", "ground-v1", null, null, null, null, null, null, true, 0, 6,
                        ContractSnapshotCanonicalizer.sha256(text), text, 1, now, 0));
            }
        }
        finding = savedFinding; check = savedCheck;
        runService = new ContractReviewRunService(auth, mock(ContractReviewRunCreateTransactionCoordinator.class),
                runs, runs, runs, runs, new ReviewRunSnapshotIdentityFactory());
        outputs = new ReviewStepOutputReadService(runService, results, results, tasks, checks, idempotency);
        frozen = new FrozenReviewInputLoader(runService, parses, new ContractSnapshotDecoder(canonicalizer));
    }
}
