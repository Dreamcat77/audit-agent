package com.fuquan.audit.contract.application.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.contract.application.ReviewRequestMetadata;
import com.fuquan.audit.contract.application.project.port.*;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResolver;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentityResolver;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.contract.application.reviewrun.port.*;
import com.fuquan.audit.contract.domain.*;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.application.reviewrun.*;
import com.fuquan.audit.contract.infra.memory.reviewrun.InMemoryContractReviewRunRepositories;
import com.fuquan.audit.contract.infra.memory.*;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class ProjectReviewSessionServiceTest {
    final Instant now = Instant.parse("2026-09-16T00:00:00Z");
    final UUID projectId = UUID.randomUUID(), contractId = UUID.randomUUID();
    final String actor = UUID.randomUUID().toString(), sha = "a".repeat(64);
    final ProjectRepository projects = mock(ProjectRepository.class);
    final ProjectFileRepository files = mock(ProjectFileRepository.class);
    final ProjectFileParseResultRepository parses = mock(ProjectFileParseResultRepository.class);
    final ProjectFileRoleDecisionRepository roles = mock(ProjectFileRoleDecisionRepository.class);
    final ProjectFileIdentityDecisionRepository identities = mock(ProjectFileIdentityDecisionRepository.class);
    final ProjectFileIdentityAssessmentRepository assessments = mock(ProjectFileIdentityAssessmentRepository.class);
    final ProjectContractSnapshotLockPort associations = mock(ProjectContractSnapshotLockPort.class);
    final ContractSnapshotLockPort contracts = mock(ContractSnapshotLockPort.class);
    final AuthContext auth = mock(AuthContext.class);
    final InMemoryContractReviewStore store = new InMemoryContractReviewStore();
    final InMemoryContractReviewSessionRepository sessions = new InMemoryContractReviewSessionRepository(store);
    final InMemoryProjectSessionBindingRepository bindings = new InMemoryProjectSessionBindingRepository();
    final InMemoryMaterialBindingRepository legacy = new InMemoryMaterialBindingRepository(store);
    final InMemoryIdempotencyAdapter idempotency = new InMemoryIdempotencyAdapter();
    final ReviewRequestMetadata metadata = new ReviewRequestMetadata("request", "trace", null);
    final ProjectSessionReadiness readiness = new ProjectSessionReadiness(projects, associations, contracts,
            files, parses, new EffectiveDocumentRoleResolver(parses, roles),
            new EffectiveProjectIdentityResolver(parses, assessments, identities));
    final ProjectReviewSessionService service = new ProjectReviewSessionService(sessions, bindings, legacy,
            readiness, idempotency, mock(OperationAuditPort.class), auth, Clock.fixed(now, ZoneOffset.UTC));
    UUID body, award;

    @BeforeEach void setup() {
        when(auth.currentUser()).thenReturn(Optional.of(new CurrentUser(actor, "auditor", "审计员", Set.of("AUDITOR"))));
        when(projects.findByIdForUpdate(projectId)).thenReturn(Optional.of(new Project(projectId, "Project", "P-1", "GOODS", actor, 1, now, now)));
        when(associations.findByContractIdForUpdate(contractId)).thenReturn(Optional.of(new ProjectContract(projectId, contractId, UUID.randomUUID(), actor, now)));
        when(contracts.findByIdForUpdate(contractId)).thenReturn(Optional.of(new Contract(contractId, "C-1", "Contract", "GOODS", "A", "B", null,
                Money.of(new BigDecimal("100.00")), null, "READY_FOR_REVIEW", 0, now, now, List.of(), null)));
        body = material(DocumentRole.CONTRACT_BODY); award = material(DocumentRole.AWARD_NOTICE);
    }

    @Test void bindsExactImmutableProjectFactsAndCanReopen() {
        var result = create("create");
        var binding = result.bindings().stream().filter(b -> b.projectFileId().equals(body)).findFirst().orElseThrow();
        assertEquals(sha, binding.fileSha256());
        assertEquals(parses.findLatestByFileId(body).orElseThrow().parseResultId(), binding.parseResultId());
        assertEquals(actor, binding.boundBy());
        assertEquals(ReviewSessionSourceKind.PROJECT_FILE_REFERENCE, result.session().sourceKind());
        assertEquals(result, service.get(projectId, contractId, result.session().id()));
        assertTrue(store.findDocumentsBySession(result.session().id()).isEmpty());
    }

    @Test void wrongProjectFileIsNotFound() {
        when(files.findByProjectIdAndIdForUpdate(projectId, body)).thenReturn(Optional.empty());
        reject(() -> create("bad"), BusinessErrorCode.NOT_FOUND);
    }
    @Test void wrongContractAssociationIsNotFound() {
        when(associations.findByContractIdForUpdate(contractId)).thenReturn(Optional.of(new ProjectContract(UUID.randomUUID(), contractId, UUID.randomUUID(), actor, now)));
        reject(() -> create("bad"), BusinessErrorCode.NOT_FOUND);
    }
    @Test void unavailableFileFailsClosed() {
        var file = files.findByProjectIdAndIdForUpdate(projectId, body).orElseThrow();
        when(files.findByProjectIdAndIdForUpdate(projectId, body)).thenReturn(Optional.of(file.fail("FAILED", now)));
        reject(() -> create("bad"), BusinessErrorCode.CONFLICT);
    }
    @Test void parseShaMismatchFailsClosed() {
        var p = parses.findLatestByFileId(body).orElseThrow();
        when(parses.findLatestByFileId(body)).thenReturn(Optional.of(new ProjectFileParseResult(p.parseResultId(), projectId, body, "b".repeat(64), 1,
                p.parseOrigin(), p.parseStatus(), p.parseMethod(), p.engine(), p.engineVersion(), p.canonicalText(), p.canonicalTextSha256(), 1, 1L, null, null, now, now, 0)));
        reject(() -> create("bad"), BusinessErrorCode.CONFLICT);
    }
    @Test void missingRoleDecisionFailsClosed() {
        when(roles.findLatestByFileId(body)).thenReturn(Optional.empty());
        reject(() -> create("bad"), BusinessErrorCode.CONFLICT);
    }
    @Test void missingHumanIdentityFailsClosed() {
        when(identities.findLatestByFileId(body)).thenReturn(Optional.empty());
        reject(() -> create("bad"), BusinessErrorCode.CONFLICT);
    }
    @Test void duplicateSelectionRejected() {
        reject(() -> service.create(projectId, contractId, "Review", null, List.of(body, body), "bad", metadata), BusinessErrorCode.INVALID_ARGUMENT);
    }
    @Test void replayDoesNotRevalidateDriftedCurrentAuthorityAndChangedCommandConflicts() {
        var first = create("stable");
        when(parses.findLatestByFileId(body)).thenReturn(Optional.empty());
        assertEquals(first.session().id(), create("stable").session().id());
        reject(() -> service.create(projectId, contractId, "Changed", null, List.of(body, award), "stable", metadata), BusinessErrorCode.CONFLICT);
        assertEquals(1, sessions.findByContractId(contractId).size());
    }
    @Test void missingAwardBlocksReadyAndValidCoreBecomesImmutableReady() {
        var one = service.create(projectId, contractId, "Review", null, List.of(body), "one", metadata);
        reject(() -> service.confirm(projectId, contractId, one.session().id(), 0, "confirm-one", metadata), BusinessErrorCode.BUSINESS_VALIDATION_FAILED);
        var result = create("two");
        var ready = service.confirm(projectId, contractId, result.session().id(), 0, "confirm-two", metadata);
        assertEquals(ReviewSessionStatus.READY, ready.session().status());
        reject(() -> service.replace(projectId, contractId, ready.session().id(), 1, List.of(body), "replace", metadata), BusinessErrorCode.CONFLICT);
    }
    @Test void changedParseCannotSilentlyReplaceSelectionDuringConfirmation() {
        var result = create("create");
        when(parses.findLatestByFileId(body)).thenReturn(Optional.empty());
        reject(() -> service.confirm(projectId, contractId, result.session().id(), 0, "confirm", metadata), BusinessErrorCode.CONFLICT);
        assertEquals(ReviewSessionStatus.PREPARING_MATERIALS, sessions.findById(result.session().id()).orElseThrow().status());
    }
    @Test void wrongSessionPathAndLegacySessionCannotBind() {
        var result = create("create");
        reject(() -> service.get(UUID.randomUUID(), contractId, result.session().id()), BusinessErrorCode.NOT_FOUND);
        var old = ContractReviewSession.create(UUID.randomUUID(), contractId, 0, "Legacy", "F02_DETERMINISTIC", null, actor, now);
        sessions.create(old);
        reject(() -> service.replace(projectId, contractId, old.id(), 0, List.of(body), "bad", metadata), BusinessErrorCode.NOT_FOUND);
    }
    @Test void existingLegacyBindingCannotBeInterpretedAlongsideProjectReferences() {
        var result=create("create");
        var document=ReviewDocument.pending(UUID.randomUUID(),result.session().id(),ReviewDocumentKind.CONTRACT_BODY,
                "CONTRACT_BODY","old.pdf","pdf","application/pdf",10,sha,sha,null,0,actor,now).markAvailable(now);
        store.createDocument(document);
        legacy.create(MaterialBinding.activeFor(document,now));
        reject(() -> service.get(projectId,contractId,result.session().id()),BusinessErrorCode.CONFLICT);
        reject(() -> service.replace(projectId,contractId,result.session().id(),0,List.of(body),"mix",metadata),BusinessErrorCode.CONFLICT);
    }
    @Test void replayOfEarlierSelectionReturnsItsOriginalProvenanceAfterReplacement() {
        var first=create("create");
        var old=service.replace(projectId,contractId,first.session().id(),0,List.of(body),"first-replace",metadata);
        service.replace(projectId,contractId,first.session().id(),1,List.of(award),"second-replace",metadata);
        var replay=service.replace(projectId,contractId,first.session().id(),0,List.of(body),"first-replace",metadata);
        assertEquals(old.bindings().get(0).id(),replay.bindings().get(0).id());
        assertEquals(body,replay.bindings().get(0).projectFileId());
        assertEquals(old,replay);
        assertEquals(first,create("create"));
        reject(() -> service.confirm(projectId,contractId,first.session().id(),replay.session().versionNo(),"stale-confirm",metadata),BusinessErrorCode.CONFLICT);
        assertEquals(award,service.get(projectId,contractId,first.session().id()).bindings().get(0).projectFileId());
    }
    @Test void selectionReplacementPreservesHistoryAndIndependentFrozenRunMaterial() {
        var result = create("create");
        var b = result.bindings().get(0);
        var runMaterial = new ContractReviewRunMaterial(UUID.randomUUID(), UUID.randomUUID(), 1, projectId,
                b.projectFileId(), b.fileSha256(), b.parseResultId(), b.parseVersion(), b.canonicalTextSha256(), b.effectiveRole(), b.roleDecisionId(), b.identityDecisionId(), null, actor, now);
        var runs=new InMemoryContractReviewRunRepositories();
        var snapshotIdentity=new ReviewRunSnapshotIdentityFactory();
        String contractHash=ContractSnapshotCanonicalizer.sha256("{}");
        String input=snapshotIdentity.identity(1,projectId,contractId,0,contractHash,null,null,List.of(runMaterial),"profile","rules","models");
        var run=new ContractReviewRun(runMaterial.runId(),projectId,contractId,0,null,null,1,"{}",contractHash,
                "profile","rules","models",input,actor,ContractReviewRunStatus.PENDING,now,null,null,null,null,null,0);
        runs.insert(run); runs.insert(runMaterial);
        var manifest=new ReviewRunManifestFactory().create(run.runId(),now);
        manifest.steps().forEach(runs::insert); manifest.attempts().forEach(runs::insert);
        var runReader=new ContractReviewRunService(auth,mock(ContractReviewRunCreateTransactionCoordinator.class),runs,runs,runs,runs,snapshotIdentity);
        var frozen=runReader.get(projectId,contractId,run.runId());
        var changed = service.replace(projectId, contractId, result.session().id(), 0, List.of(body), "replace", metadata);
        assertEquals(1, changed.bindings().size());
        assertEquals(3, bindings.findBySessionId(result.session().id()).size());
        assertEquals(b.parseResultId(), runMaterial.parseResultId());
        assertEquals(b.projectFileId(), runMaterial.fileId());
        assertEquals(frozen,runReader.get(projectId,contractId,run.runId()));
        assertEquals(3,service.history(projectId,contractId,result.session().id()).size());
        reject(() -> service.replace(projectId, contractId, result.session().id(), 0, List.of(award), "stale", metadata), BusinessErrorCode.CONFLICT);
    }
    @Test void unauthorizedActorCannotCreateOrRead() {
        when(auth.currentUser()).thenReturn(Optional.of(new CurrentUser(actor, "user", "User", Set.of("UNKNOWN"))));
        reject(() -> create("denied"), BusinessErrorCode.FORBIDDEN);
    }
    @Test void databaseCasLoserIsConflictAndLeavesSelectionIntact() {
        var first=create("create");
        var raced=spy(sessions);
        doThrow(new com.fuquan.audit.contract.application.port.ContractReviewConcurrencyException("CAS lost"))
                .when(raced).advancePreparingVersion(eq(first.session().id()),eq(0L),any());
        var racedService=new ProjectReviewSessionService(raced,bindings,legacy,readiness,idempotency,
                mock(OperationAuditPort.class),auth,Clock.fixed(now,ZoneOffset.UTC));
        reject(() -> racedService.replace(projectId,contractId,first.session().id(),0,List.of(body),"db-race",metadata),BusinessErrorCode.CONFLICT);
        assertEquals(first,service.get(projectId,contractId,first.session().id()));
    }
    @Test void concurrentSelectionSameVersionHasOneWinner() throws Exception {
        var result = create("create");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i=0;i<2;i++) { final String key="race-"+i; tasks.add(pool.submit(() -> { start.await(); try {
                service.replace(projectId, contractId, result.session().id(), 0, List.of(body), key, metadata); return true;
            } catch (BusinessException | com.fuquan.audit.contract.application.port.ContractReviewConcurrencyException expected) { return false; } })); }
            start.countDown();
            assertEquals(1, (tasks.get(0).get() ? 1 : 0) + (tasks.get(1).get() ? 1 : 0));
            assertEquals(1, service.get(projectId, contractId, result.session().id()).bindings().size());
        } finally { pool.shutdownNow(); }
    }

    private ProjectReviewSessionService.View create(String key) { return service.create(projectId, contractId, "Review", null, List.of(body, award), key, metadata); }
    private void reject(Runnable operation, BusinessErrorCode code) { assertEquals(code, assertThrows(BusinessException.class, operation::run).errorCode()); }
    private UUID material(DocumentRole role) {
        UUID id=UUID.randomUUID(), parseId=UUID.randomUUID(), assessmentId=UUID.randomUUID();
        String text="Synthetic "+role;
        var file=new ProjectFile(id, projectId, null, "file.pdf", "file.pdf", "application/pdf", ProjectFileCategory.UNKNOWN,
                ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED, sha, sha, 100, text, null, now, now);
        when(files.findByProjectIdAndIdForUpdate(projectId,id)).thenReturn(Optional.of(file));
        when(parses.findLatestByFileId(id)).thenReturn(Optional.of(new ProjectFileParseResult(parseId,projectId,id,sha,1,ProjectFileParseOrigin.CURRENT_PIPELINE,
                ProjectFileParseStatus.PARSED,ProjectFileParseMethod.PDF_TEXT,"PDFBox","1",text,ContractSnapshotCanonicalizer.sha256(text),1,1L,null,null,now,now,0)));
        when(roles.findLatestByFileId(id)).thenReturn(Optional.of(new ProjectFileRoleDecision(UUID.randomUUID(),projectId,id,sha,parseId,1,UUID.randomUUID(),role,
                RoleDecisionStatus.CONFIRMED,actor,now,null,null,1)));
        var assessment=new ProjectFileIdentityAssessment(assessmentId,projectId,1,id,sha,parseId,1,ProjectIdentityAssessmentSource.DETERMINISTIC,"1","1",
                ProjectIdentityAssessmentStatus.LIKELY_SAME_PROJECT,sha,null,now,now,0);
        when(assessments.findById(assessmentId)).thenReturn(Optional.of(assessment));
        when(assessments.findLatestByFileId(id)).thenReturn(Optional.of(assessment));
        when(identities.findLatestByFileId(id)).thenReturn(Optional.of(new ProjectFileIdentityDecision(UUID.randomUUID(),projectId,1,id,sha,parseId,1,assessmentId,
                ProjectIdentityResolution.CONFIRM_SAME_PROJECT,actor,now,null,null,1)));
        return id;
    }
}
