package com.fuquan.audit.contract.application.project;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateAssembler;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentityResolver;
import com.fuquan.audit.contract.application.project.port.*;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResolver;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.contract.infra.memory.project.InMemoryContractCandidateSupportAuthorityRepository;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectRepositories;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real local repositories/validators; the transaction observer proves boundaries, not DM8 rollback. */
final class ContractCandidateSupportTestSupport {
    static final UUID PROJECT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID CANDIDATE = UUID.fromString("10000000-0000-0000-0000-000000000002");
    static final UUID A = UUID.fromString("70000000-0000-0000-0000-000000000003");
    static final UUID PRIMARY = UUID.fromString("80000000-0000-0000-0000-000000000004");
    static final UUID B = UUID.fromString("f0000000-0000-0000-0000-000000000005");
    static final Instant NOW = Instant.parse("2026-09-20T03:00:00Z");
    static final ProjectRequestMetadata META = new ProjectRequestMetadata("request", "trace", null);
    static final CurrentUser ACTOR = new CurrentUser("test-actor", "test.actor", "Actor", Set.of("AUDITOR"));
    static final String PRIVATE_TEXT = "synthetic-private-parsed-text";
    static final String PRIVATE_NAME = "synthetic-private-name.pdf";

    static final class Fixture {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final InMemoryProjectRepositories memory = new InMemoryProjectRepositories();
        final ProjectRepository projects = spy(memory.projectRepository());
        final ProjectFileRepository files = spy(memory.projectFileRepository());
        final ContractCandidateRepository candidates = spy(memory.contractCandidateRepository());
        final ExtractionResultRepository extractions = mock(ExtractionResultRepository.class);
        final InMemoryContractCandidateSupportAuthorityRepository supports =
                spy(new InMemoryContractCandidateSupportAuthorityRepository());
        final InMemoryIdempotencyAdapter idempotency = spy(new InMemoryIdempotencyAdapter());
        final List<OperationAuditEvent> audits = new ArrayList<>();
        final List<Source> sources;
        final CandidateSourceAuthorityValidator primaryAuthority;
        final CandidateSupportAuthorityValidator supportAuthority;
        final BoundaryTransactions transactions = new BoundaryTransactions();
        final ContractCandidateSupportReplacementTransactionCoordinator coordinator;
        final ContractCandidateSupportReplacementService service;
        String failureAfter;

        Fixture() {
            this(2);
        }

        Fixture(int supportCount) {
            List<Source> initialized = new ArrayList<>();
            initialized.add(new Source(PRIMARY, DocumentRole.CONTRACT_BODY, "100"));
            for (int index = 0; index < supportCount; index++) {
                UUID id = index == 0 ? A : index == 1 ? B : new UUID(0x4000000000000000L, index);
                initialized.add(new Source(id, DocumentRole.AWARD_NOTICE, index == 1 ? "99" : "98"));
            }
            sources = List.copyOf(initialized);
            Project project = new Project(PROJECT, "Synthetic project", "SYN-1", null, ACTOR.userId(),
                    1, NOW, NOW);
            projects.insert(project);
            sources.forEach(source -> files.insert(source.file()));
            var parses = mock(ProjectFileParseResultRepository.class);
            var roles = mock(ProjectFileRoleDecisionRepository.class);
            var identities = mock(ProjectFileIdentityDecisionRepository.class);
            var assessments = mock(ProjectFileIdentityAssessmentRepository.class);
            for (Source source : sources) {
                when(parses.findLatestByFileId(source.id)).thenAnswer(call -> Optional.of(source.parse()));
                when(roles.findLatestByFileId(source.id)).thenAnswer(call -> Optional.of(source.role()));
                when(identities.findLatestByFileId(source.id)).thenAnswer(call -> Optional.of(source.identity()));
                when(assessments.findLatestByFileId(source.id)).thenAnswer(call -> Optional.of(source.assessment()));
            }
            when(roles.findById(any())).thenAnswer(call -> sources.stream()
                    .filter(s -> s.roleId.equals(call.getArgument(0))).map(Source::role).findFirst());
            when(identities.findById(any())).thenAnswer(call -> sources.stream()
                    .filter(s -> s.identityId.equals(call.getArgument(0))).map(Source::identity).findFirst());
            when(assessments.findById(any())).thenAnswer(call -> sources.stream()
                    .filter(s -> s.assessmentId.equals(call.getArgument(0))).map(Source::assessment).findFirst());
            when(extractions.findBySourceFileIdAndParseResultId(any(), any(), any())).thenAnswer(call -> sources.stream()
                    .filter(s -> PROJECT.equals(call.getArgument(0)) && s.id.equals(call.getArgument(1))
                            && s.parseId.equals(call.getArgument(2))).findFirst()
                    .map(s -> List.copyOf(s.facts)).orElseThrow());
            var effectiveRoles = new EffectiveDocumentRoleResolver(parses, roles);
            var effectiveIdentities = new EffectiveProjectIdentityResolver(parses, assessments, identities);
            supportAuthority = spy(new CandidateSupportAuthorityValidator(projects, files, parses, roles,
                    identities, effectiveRoles, effectiveIdentities, extractions, supports));
            primaryAuthority = spy(new CandidateSourceAuthorityValidator(files, parses, roles,
                    effectiveRoles, projects, identities, effectiveIdentities, supportAuthority));
            var assembler = new ContractCandidateAssembler(Clock.fixed(NOW, ZoneOffset.UTC));
            Source primary = sources.get(0);
            candidates.insertIfAbsent(assembler.assemble(CANDIDATE, PROJECT, primary.file(), primary.parseId,
                    primary.roleId, primary.identityId, "a".repeat(64), primary.facts));
            recordCalls();
            var target = new ContractCandidateSupportReplacementTransactionCoordinator(projects, files, candidates,
                    primaryAuthority, supportAuthority, extractions, supports, assembler, idempotency,
                    event -> { audits.add(event); after("audit"); }, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
            ProxyFactory proxy = new ProxyFactory(target);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
            coordinator = (ContractCandidateSupportReplacementTransactionCoordinator) proxy.getProxy();
            service = new ContractCandidateSupportReplacementService(() -> Optional.of(ACTOR), coordinator);
        }

        private void recordCalls() {
            doAnswer(call -> { calls.add("discover"); return call.callRealMethod(); })
                    .when(candidates).findByProjectIdAndId(PROJECT, CANDIDATE);
            doAnswer(call -> { calls.add("file:" + call.getArgument(1)); return call.callRealMethod(); })
                    .when(files).findByProjectIdAndIdForUpdate(any(), any());
            doAnswer(call -> { calls.add("project"); return call.callRealMethod(); })
                    .when(projects).findByIdForUpdate(PROJECT);
            doAnswer(call -> { calls.add("candidate"); return call.callRealMethod(); })
                    .when(candidates).findByProjectIdAndIdForUpdate(PROJECT, CANDIDATE);
            doAnswer(call -> { calls.add("primary-authority"); return call.callRealMethod(); })
                    .when(primaryAuthority).snapshot(any());
            doAnswer(call -> { calls.add("support-authority"); return call.callRealMethod(); })
                    .when(supportAuthority).resolveRequested(any(), anyList());
            doAnswer(call -> { Object value = call.callRealMethod(); after("claim"); return value; })
                    .when(idempotency).claim(any());
            doAnswer(call -> { Object value = call.callRealMethod(); after("delete"); return value; })
                    .when(supports).deleteCurrentByProjectIdAndCandidateId(any(), any());
            doAnswer(call -> { Object value = call.callRealMethod(); after("cas"); return value; })
                    .when(candidates).replaceDraftDerivedFactsIfVersionMatches(any(), any(), anyLong(), any(),
                            anyLong(), any(), any());
            doAnswer(call -> { Object value = call.callRealMethod(); after("insert"); return value; })
                    .when(supports).insertAll(anyList());
            doAnswer(call -> { Object value = call.callRealMethod(); after("success"); return value; })
                    .when(idempotency).markSucceeded(any(), any(), any(), any());
        }

        void after(String stage) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new AssertionError("write outside transaction: " + stage);
            }
            calls.add(stage);
            if (stage.equals(failureAfter)) throw new IllegalStateException("injected " + stage);
        }

        ContractCandidate current() { return candidates.findByProjectIdAndId(PROJECT, CANDIDATE).orElseThrow(); }
        List<ContractCandidateSupportAuthority> rows() {
            return supports.findCurrentByProjectIdAndCandidateId(PROJECT, CANDIDATE);
        }
        ReplaceContractCandidateSupportAuthorityCommand command(String key, long version, List<UUID> ids) {
            return new ReplaceContractCandidateSupportAuthorityCommand(PROJECT, CANDIDATE, version, ids, key, META);
        }
        ContractCandidateSupportReplacementResult replace(String key, long version, List<UUID> ids) {
            return service.replace(command(key, version, ids));
        }
    }

    static final class Source {
        final UUID id;
        final DocumentRole selectedRole;
        UUID parseId = UUID.randomUUID(), roleId = UUID.randomUUID(), identityId = UUID.randomUUID();
        final UUID assessmentId = UUID.randomUUID();
        String hash;
        long projectVersion = 1;
        final List<ExtractionResult> facts = new ArrayList<>();
        Source(UUID id, DocumentRole selectedRole, String award) {
            this.id = id; this.selectedRole = selectedRole; this.hash = sha(id.toString());
            facts.add(fact("AWARD_AMOUNT", award, new BigDecimal(award), null));
            facts.add(fact("AWARD_SUPPLIER", "synthetic-private-supplier", null, null));
            facts.add(fact("AWARD_SUPPLIER_CREDIT_CODE", "synthetic-private-credit", null, null));
            facts.add(fact("CONTRACT_NO", selectedRole == DocumentRole.CONTRACT_BODY ? "PRIMARY" : "FORBIDDEN", null, null));
            facts.add(fact("LINE_ITEM_NAME", selectedRole == DocumentRole.CONTRACT_BODY ? "Primary line" : "Forbidden line",
                    null, "line-1"));
        }
        ExtractionResult fact(String type, String value, BigDecimal amount, String group) {
            return new ExtractionResult(UUID.randomUUID(), PROJECT, id, parseId,
                    selectedRole == DocumentRole.CONTRACT_BODY ? CANDIDATE : null,
                    sha(id + type), "extract-v1", type, group, value, value, amount,
                    "synthetic-private-excerpt", 1, 1, "page:1", NOW);
        }
        ProjectFile file() {
            return new ProjectFile(id, PROJECT, null, PRIVATE_NAME, PRIVATE_NAME, "application/pdf",
                    selectedRole == DocumentRole.CONTRACT_BODY ? ProjectFileCategory.CONTRACT_BODY : ProjectFileCategory.AWARD_NOTICE,
                    ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED, sha("storage-" + id), hash,
                    100, PRIVATE_TEXT, null, NOW, NOW);
        }
        ProjectFileParseResult parse() {
            return new ProjectFileParseResult(parseId, PROJECT, id, hash, 1, ProjectFileParseOrigin.CURRENT_PIPELINE,
                    ProjectFileParseStatus.PARSED, ProjectFileParseMethod.PDF_TEXT, "TEST", null, PRIVATE_TEXT,
                    sha(PRIVATE_TEXT), 1, 1L, null, null, NOW, NOW, 0);
        }
        ProjectFileRoleDecision role() {
            return new ProjectFileRoleDecision(roleId, PROJECT, id, hash, parseId, 1, assessmentId,
                    selectedRole, RoleDecisionStatus.CONFIRMED, ACTOR.userId(), NOW, null, null, 1);
        }
        ProjectFileIdentityDecision identity() {
            return new ProjectFileIdentityDecision(identityId, PROJECT, projectVersion, id, hash, parseId, 1,
                    assessmentId, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, ACTOR.userId(), NOW, null, null, 1);
        }
        ProjectFileIdentityAssessment assessment() {
            return new ProjectFileIdentityAssessment(assessmentId, PROJECT, projectVersion, id, hash, parseId, 1,
                    ProjectIdentityAssessmentSource.DETERMINISTIC, "test-v1", "human-v1",
                    ProjectIdentityAssessmentStatus.LIKELY_SAME_PROJECT, "e".repeat(64), null, NOW, NOW, 0);
        }
    }

    static final class BoundaryTransactions extends AbstractPlatformTransactionManager {
        int begins, commits, rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            if (definition.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED) {
                throw new AssertionError("replacement must use REQUIRED");
            }
            begins++;
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }

    static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
}
