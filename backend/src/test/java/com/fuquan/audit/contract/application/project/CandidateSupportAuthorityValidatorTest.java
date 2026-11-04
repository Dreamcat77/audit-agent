package com.fuquan.audit.contract.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentityResolver;
import com.fuquan.audit.contract.application.project.port.ContractCandidateSupportAuthorityRepository;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityAssessmentRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResolver;
import com.fuquan.audit.contract.domain.project.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CandidateSupportAuthorityValidatorTest {
    private static final UUID PROJECT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CANDIDATE = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID PRIMARY = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID A = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("f0000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final String TEXT = "synthetic-private-parse-text";
    private static final String EXCERPT = "synthetic-private-excerpt";
    private static final String VALUE = "synthetic-private-supplier";

    @Test
    void emptyReplacementHasVersionedCompatibleFingerprintAndNoFactRead() {
        Fixture f = new Fixture();
        var resolved = f.validator.resolveRequested(PROJECT, List.of());
        assertThat(resolved.authorityMode()).isEqualTo(CandidateAuthorityMode.PRIMARY_ONLY_COMPAT);
        assertThat(resolved.supports()).isEmpty();
        assertThat(resolved.supportAuthorityFingerprint()).matches("[0-9a-f]{64}");
        assertThat(f.validator.resolveRequested(PROJECT, List.of())).isEqualTo(resolved);
        verify(f.extractions, never()).findBySourceFileIdAndParseResultId(any(), any(), any());
    }

    @Test
    void resolvesOnlyRequestedExactHumanAuthorityAndEligibleFacts() throws Exception {
        Fixture f = new Fixture();
        Source source = f.sources.get(0);
        source.facts.add(source.fact("CONTRACT_NO", "d", null));
        source.facts.add(source.fact("LINE_ITEM_NAME", "e", null));
        var resolved = f.validator.resolveRequested(PROJECT, List.of(A));
        var support = resolved.supports().get(0);
        assertThat(resolved.authorityMode()).isEqualTo(CandidateAuthorityMode.EXPLICIT_SUPPORT_AUTHORITY);
        assertThat(resolved.supports()).hasSize(1);
        assertThat(support.projectId()).isEqualTo(PROJECT);
        assertThat(support.sourceFileId()).isEqualTo(A);
        assertThat(support.fileSha256()).isEqualTo(source.hash);
        assertThat(support.parseResultId()).isEqualTo(source.parseId);
        assertThat(support.parseVersion()).isEqualTo(1);
        assertThat(support.projectVersionNo()).isEqualTo(1);
        assertThat(support.effectiveRole()).isEqualTo(DocumentRole.AWARD_NOTICE);
        assertThat(support.roleDecisionId()).isEqualTo(source.roleId);
        assertThat(support.identityResolution()).isEqualTo(ProjectIdentityResolution.CONFIRM_SAME_PROJECT);
        assertThat(support.identityDecisionId()).isEqualTo(source.identityId);
        assertThat(support.eligibleFactIds()).containsExactlyElementsOf(
                source.facts.subList(0, 3).stream().map(ExtractionResult::id).toList());
        assertThat(support.sourceFactFingerprint()).matches("[0-9a-f]{64}");
        assertThat(resolved.supportAuthorityFingerprint()).matches("[0-9a-f]{64}");
        assertThat(new ObjectMapper().writeValueAsString(resolved)).doesNotContain(TEXT, EXCERPT, VALUE, "98.00");
        assertThat(resolved.toString()).doesNotContain(TEXT, EXCERPT, VALUE, "98.00");
        verify(f.extractions).findBySourceFileIdAndParseResultId(PROJECT, A, source.parseId);
        verify(f.extractions, never()).findByProjectId(any());
        verify(f.extractions, never()).findBySourceFileId(any(), any());
        verify(f.extractions, never()).findBySourceFileIdAndParseResultId(PROJECT, B, f.sources.get(1).parseId);
    }

    @Test
    void canonicalUuidTextOrderAndFactOrderMakeDuplicateReversedRequestsEquivalent() {
        Fixture f = new Fixture();
        var first = f.validator.resolveRequested(PROJECT, List.of(B, A, B));
        f.sources.forEach(source -> Collections.reverse(source.facts));
        var second = f.validator.resolveRequested(PROJECT, List.of(A, B));
        assertThat(first).isEqualTo(second);
        assertThat(first.supports()).extracting(ResolvedCandidateSupportAuthority::sourceFileId)
                .containsExactly(A, B);
        assertThatThrownBy(() -> first.supports().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.supports().get(0).eligibleFactIds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    enum InvalidAuthority {
        MISSING_PROJECT, FOREIGN_PROJECT, FOREIGN_FILE_RETURNED, UNAVAILABLE, NON_PARSED_FILE,
        MISSING_PARSE, EMPTY_PARSE, STALE_PARSE_TEXT, PARSE_PROJECT, PARSE_FILE, HASH_MISMATCH,
        ROLE_PARSE_VERSION, WRONG_ROLE, CLASSIFIER_ONLY, MACHINE_ONLY, UNKNOWN_ROLE,
        MISSING_ROLE_ROW, STALE_ROLE_ROW, ROLE_PROJECT, ROLE_FILE, ROLE_HASH,
        NOT_SAME_PROJECT, MISSING_IDENTITY, MISSING_IDENTITY_ROW, STALE_IDENTITY_ROW,
        IDENTITY_PROJECT, IDENTITY_FILE, IDENTITY_HASH, IDENTITY_PARSE_VERSION,
        PROJECT_VERSION, UNCONFIRMED_PROJECT, FACT_PROJECT, FACT_FILE, FACT_PARSE, BOUND_SUPPORT_FACT
    }

    @ParameterizedTest
    @EnumSource(InvalidAuthority.class)
    void rejectsEachInvalidSourceWithoutDroppingItFromTheRequestedSet(InvalidAuthority invalid) {
        Fixture f = new Fixture();
        Source s = f.sources.get(1);
        switch (invalid) {
            case MISSING_PROJECT -> f.project = null;
            case FOREIGN_PROJECT -> s.visible = false;
            case FOREIGN_FILE_RETURNED -> s.fileProject = UUID.randomUUID();
            case UNAVAILABLE -> s.ingestion = ProjectFileIngestionStatus.UPLOAD_FAILED;
            case NON_PARSED_FILE -> s.fileStatus = ProjectFileParseStatus.PENDING;
            case MISSING_PARSE -> s.parsePresent = false;
            case EMPTY_PARSE -> s.parseStatus = ProjectFileParseStatus.FAILED;
            case STALE_PARSE_TEXT -> s.parseText = "synthetic-new-parse";
            case PARSE_PROJECT -> s.parseProject = UUID.randomUUID();
            case PARSE_FILE -> s.parseFile = UUID.randomUUID();
            case HASH_MISMATCH -> s.parseHash = "b".repeat(64);
            case ROLE_PARSE_VERSION -> s.roleParseVersion = 2;
            case WRONG_ROLE -> s.role = DocumentRole.CONTRACT_BODY;
            case CLASSIFIER_ONLY -> { s.rolePresent = false; s.origin = ProjectFileParseOrigin.LEGACY_IMPORTED; }
            case MACHINE_ONLY -> s.rolePresent = false;
            case UNKNOWN_ROLE -> s.role = DocumentRole.UNKNOWN;
            case MISSING_ROLE_ROW -> s.roleRowPresent = false;
            case STALE_ROLE_ROW -> s.roleRowId = UUID.randomUUID();
            case ROLE_PROJECT -> s.roleProject = UUID.randomUUID();
            case ROLE_FILE -> s.roleFile = UUID.randomUUID();
            case ROLE_HASH -> s.roleHash = "b".repeat(64);
            case NOT_SAME_PROJECT -> s.resolution = ProjectIdentityResolution.CONFIRM_NOT_SAME_PROJECT;
            case MISSING_IDENTITY -> s.identityPresent = false;
            case MISSING_IDENTITY_ROW -> s.identityRowPresent = false;
            case STALE_IDENTITY_ROW -> s.identityRowId = UUID.randomUUID();
            case IDENTITY_PROJECT -> s.identityProject = UUID.randomUUID();
            case IDENTITY_FILE -> s.identityFile = UUID.randomUUID();
            case IDENTITY_HASH -> s.identityHash = "b".repeat(64);
            case IDENTITY_PARSE_VERSION -> s.identityParseVersion = 2;
            case PROJECT_VERSION -> s.identityProjectVersion = 2;
            case UNCONFIRMED_PROJECT -> f.project = Project.blank(PROJECT, "synthetic-user", NOW);
            case FACT_PROJECT -> s.facts.set(0, changedFact(s.facts.get(0), "project"));
            case FACT_FILE -> s.facts.set(0, changedFact(s.facts.get(0), "file"));
            case FACT_PARSE -> s.facts.set(0, changedFact(s.facts.get(0), "parse"));
            case BOUND_SUPPORT_FACT -> s.facts.set(0, changedFact(s.facts.get(0), "candidate"));
        }
        BusinessErrorCode code = invalid == InvalidAuthority.MISSING_PROJECT
                || invalid == InvalidAuthority.FOREIGN_PROJECT ? BusinessErrorCode.NOT_FOUND : BusinessErrorCode.CONFLICT;
        assertThatThrownBy(() -> f.validator.resolveRequested(PROJECT, List.of(A, B)))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(code);
                    assertThat(error.getMessage()).doesNotContain(TEXT, EXCERPT, VALUE);
                });
    }

    @Test
    void noEligibleAwardFactsIsValidAndDisallowedFactsCannotChangeAuthorityFingerprint() {
        Fixture f = new Fixture();
        Source s = f.sources.get(0);
        s.facts.clear();
        var empty = f.validator.resolveRequested(PROJECT, List.of(A));
        s.facts.add(s.fact("AMOUNT_NUMERIC", "d", null));
        assertThat(f.validator.resolveRequested(PROJECT, List.of(A))).isEqualTo(empty);
        assertThat(empty.authorityMode()).isEqualTo(CandidateAuthorityMode.EXPLICIT_SUPPORT_AUTHORITY);
        assertThat(empty.supports().get(0).eligibleFactIds()).isEmpty();
    }

    enum AuthorityChange { HASH, PARSE_ID, PARSE_VERSION, ROLE_ID, IDENTITY_ID, PROJECT_VERSION }

    @ParameterizedTest
    @EnumSource(AuthorityChange.class)
    void mutableAuthorityChangesBothSourceAndAggregateFingerprints(AuthorityChange change) {
        Fixture f = new Fixture();
        var before = f.validator.resolveRequested(PROJECT, List.of(A));
        Source s = f.sources.get(0);
        switch (change) {
            case HASH -> s.hash = s.parseHash = s.roleHash = s.identityHash = "b".repeat(64);
            case PARSE_ID -> {
                s.parseId = UUID.randomUUID();
                s.facts.replaceAll(fact -> new ExtractionResult(fact.id(), fact.projectId(), fact.sourceFileId(),
                        s.parseId, null, fact.extractionKey(), fact.extractionVersion(), fact.factType(),
                        fact.groupKey(), fact.valueText(), fact.normalizedValue(), fact.valueAmount(),
                        fact.sourceText(), fact.pageNumber(), fact.paragraphIndex(), fact.sourceLocator(), fact.createdAt()));
            }
            case PARSE_VERSION -> s.parseVersion = s.roleParseVersion = s.identityParseVersion = 2;
            case ROLE_ID -> s.roleId = s.roleRowId = UUID.randomUUID();
            case IDENTITY_ID -> s.identityId = s.identityRowId = UUID.randomUUID();
            case PROJECT_VERSION -> {
                f.project = f.project.confirmMetadata(1, "Synthetic project", "SYN-2", null, NOW);
                s.identityProjectVersion = 2;
            }
        }
        var after = f.validator.resolveRequested(PROJECT, List.of(A));
        assertThat(after.supportAuthorityFingerprint()).isNotEqualTo(before.supportAuthorityFingerprint());
        assertThat(after.supports().get(0).sourceFactFingerprint())
                .isNotEqualTo(before.supports().get(0).sourceFactFingerprint());
    }

    enum FactChange { ID, KEY, VERSION, TYPE, GROUP, VALUE, NORMALIZED, AMOUNT, EXCERPT, PAGE, PARAGRAPH, LOCATOR }

    @ParameterizedTest
    @EnumSource(FactChange.class)
    void immutableFactContentDriftCannotHideBehindAnUnchangedExtractionKey(FactChange change) {
        Fixture f = new Fixture();
        var before = f.validator.resolveRequested(PROJECT, List.of(A));
        Source s = f.sources.get(0);
        s.facts.set(0, changedFact(s.facts.get(0), change.name()));
        var after = f.validator.resolveRequested(PROJECT, List.of(A));
        assertThat(after.supportAuthorityFingerprint()).isNotEqualTo(before.supportAuthorityFingerprint());
        assertThat(after.supports().get(0).sourceFactFingerprint())
                .isNotEqualTo(before.supports().get(0).sourceFactFingerprint());
    }

    @Test
    void historicalEmptyAndExplicitClearValidateWithoutInventingSupportRows() {
        Fixture f = new Fixture();
        var empty = f.validator.resolveRequested(PROJECT, List.of());
        assertThat(f.validator.validateCurrent(candidate(null, null))).isEqualTo(empty);
        assertThat(f.validator.validateCurrent(candidate(3L, empty.supportAuthorityFingerprint()))).isEqualTo(empty);
        reject(() -> f.validator.validateCurrent(candidate(3L, "a".repeat(64))));
    }

    @Test
    void exactCurrentCohortValidatesIndependentlyOfCandidateVersion() {
        Fixture f = new Fixture();
        var resolved = f.validator.resolveRequested(PROJECT, List.of(A, B));
        f.rows = resolved.supports().stream().map(s -> row(s, 3)).toList();
        assertThat(f.validator.validateCurrent(candidate(3L, resolved.supportAuthorityFingerprint())))
                .isEqualTo(resolved);
    }

    enum CohortChange { VERSION, MISSING, EXTRA, DUPLICATE, ROW_FINGERPRINT, AGGREGATE_FINGERPRINT,
        PROJECT, CANDIDATE, FILE_HASH, PARSE_ID, PARSE_VERSION, PROJECT_VERSION, ROLE_ID, IDENTITY_ID,
        LEGACY_WITH_ROWS, CURRENT_FACT_DRIFT, CURRENT_ROLE_DRIFT, CURRENT_IDENTITY_DRIFT }

    @ParameterizedTest
    @EnumSource(CohortChange.class)
    void rejectsIncompleteCorruptOrStaleCurrentCohort(CohortChange change) {
        Fixture f = new Fixture();
        var resolved = f.validator.resolveRequested(PROJECT, List.of(A, B));
        f.rows = new ArrayList<>(resolved.supports().stream().map(s -> row(s, 3)).toList());
        ContractCandidate current = candidate(3L, resolved.supportAuthorityFingerprint());
        switch (change) {
            case MISSING -> f.rows.remove(1);
            case EXTRA -> {
                resolved = f.validator.resolveRequested(PROJECT, List.of(A));
                current = candidate(3L, resolved.supportAuthorityFingerprint());
            }
            case DUPLICATE -> f.rows.set(1, f.rows.get(0));
            case AGGREGATE_FINGERPRINT -> current = candidate(3L, "a".repeat(64));
            case LEGACY_WITH_ROWS -> current = candidate(null, null);
            case CURRENT_FACT_DRIFT -> f.sources.get(0).facts.clear();
            case CURRENT_ROLE_DRIFT -> f.sources.get(0).roleId = f.sources.get(0).roleRowId = UUID.randomUUID();
            case CURRENT_IDENTITY_DRIFT -> f.sources.get(0).identityId = f.sources.get(0).identityRowId = UUID.randomUUID();
            default -> f.rows.set(0, changedRow(f.rows.get(0), change));
        }
        ContractCandidate input = current;
        reject(() -> f.validator.validateCurrent(input));
    }

    private static void reject(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
    }

    private static ContractCandidate candidate(Long version, String fingerprint) {
        return new ContractCandidate(CANDIDATE, PROJECT, PRIMARY, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, List.of(),
                CandidatePromotionState.DRAFT, 17, null, null, null, null, NOW, NOW, version, fingerprint);
    }

    private static ContractCandidateSupportAuthority row(ResolvedCandidateSupportAuthority s, long version) {
        return new ContractCandidateSupportAuthority(UUID.randomUUID(), s.projectId(), CANDIDATE,
                s.sourceFileId(), s.fileSha256(), s.parseResultId(), s.parseVersion(), s.projectVersionNo(),
                s.effectiveRole(), s.roleDecisionId(), s.identityResolution(), s.identityDecisionId(),
                s.sourceFactFingerprint(), version, "synthetic-user", NOW);
    }

    private static ContractCandidateSupportAuthority changedRow(ContractCandidateSupportAuthority r, CohortChange c) {
        return new ContractCandidateSupportAuthority(r.supportAuthorityId(),
                c == CohortChange.PROJECT ? UUID.randomUUID() : r.projectId(),
                c == CohortChange.CANDIDATE ? UUID.randomUUID() : r.candidateId(), r.sourceFileId(),
                c == CohortChange.FILE_HASH ? "b".repeat(64) : r.fileSha256(),
                c == CohortChange.PARSE_ID ? UUID.randomUUID() : r.parseResultId(),
                c == CohortChange.PARSE_VERSION ? 2 : r.parseVersion(),
                c == CohortChange.PROJECT_VERSION ? 2 : r.projectVersionNo(), r.effectiveRole(),
                c == CohortChange.ROLE_ID ? UUID.randomUUID() : r.roleDecisionId(), r.identityResolution(),
                c == CohortChange.IDENTITY_ID ? UUID.randomUUID() : r.identityDecisionId(),
                c == CohortChange.ROW_FINGERPRINT ? "c".repeat(64) : r.sourceFactFingerprint(),
                c == CohortChange.VERSION ? 4 : r.authorityVersion(), r.createdBy(), r.createdAt());
    }

    private static ExtractionResult changedFact(ExtractionResult f, String c) {
        return new ExtractionResult(c.equals("ID") ? UUID.randomUUID() : f.id(),
                c.equals("project") ? UUID.randomUUID() : f.projectId(),
                c.equals("file") ? UUID.randomUUID() : f.sourceFileId(),
                c.equals("parse") ? UUID.randomUUID() : f.parseResultId(),
                c.equals("candidate") ? CANDIDATE : f.candidateId(),
                c.equals("KEY") ? "f".repeat(64) : f.extractionKey(),
                c.equals("VERSION") ? "extract-v2" : f.extractionVersion(),
                c.equals("TYPE") ? "AWARD_SUPPLIER" : f.factType(),
                c.equals("GROUP") ? "changed" : f.groupKey(),
                c.equals("VALUE") ? "changed" : f.valueText(),
                c.equals("NORMALIZED") ? "changed" : f.normalizedValue(),
                c.equals("AMOUNT") ? new BigDecimal("99") : f.valueAmount(),
                c.equals("EXCERPT") ? "changed" : f.sourceText(),
                c.equals("PAGE") ? 2 : f.pageNumber(), c.equals("PARAGRAPH") ? 2 : f.paragraphIndex(),
                c.equals("LOCATOR") ? "page:2" : f.sourceLocator(), f.createdAt());
    }

    private static final class Fixture {
        Project project = new Project(PROJECT, "Synthetic project", "SYN-1", null, "synthetic-user", 1, NOW, NOW);
        final List<Source> sources = List.of(new Source(A), new Source(B));
        List<ContractCandidateSupportAuthority> rows = List.of();
        final ProjectRepository projects = mock(ProjectRepository.class);
        final ProjectFileRepository files = mock(ProjectFileRepository.class);
        final ProjectFileParseResultRepository parses = mock(ProjectFileParseResultRepository.class);
        final ProjectFileRoleDecisionRepository roles = mock(ProjectFileRoleDecisionRepository.class);
        final ProjectFileIdentityDecisionRepository identities = mock(ProjectFileIdentityDecisionRepository.class);
        final ProjectFileIdentityAssessmentRepository assessments = mock(ProjectFileIdentityAssessmentRepository.class);
        final ExtractionResultRepository extractions = mock(ExtractionResultRepository.class);
        final ContractCandidateSupportAuthorityRepository supports = mock(ContractCandidateSupportAuthorityRepository.class);
        final CandidateSupportAuthorityValidator validator;

        Fixture() {
            when(projects.findById(PROJECT)).thenAnswer(call -> Optional.ofNullable(project));
            for (Source s : sources) {
                when(files.findByProjectIdAndId(PROJECT, s.id)).thenAnswer(call ->
                        s.visible ? Optional.of(s.file()) : Optional.empty());
                when(parses.findLatestByFileId(s.id)).thenAnswer(call ->
                        s.parsePresent ? Optional.of(s.parse()) : Optional.empty());
                when(roles.findLatestByFileId(s.id)).thenAnswer(call ->
                        s.rolePresent ? Optional.of(s.role(s.roleId)) : Optional.empty());
                when(identities.findLatestByFileId(s.id)).thenAnswer(call ->
                        s.identityPresent ? Optional.of(s.identity(s.identityId)) : Optional.empty());
                when(assessments.findLatestByFileId(s.id)).thenAnswer(call -> Optional.of(s.assessment()));
            }
            when(roles.findById(any())).thenAnswer(call -> sources.stream()
                    .filter(s -> s.roleRowPresent && s.roleId.equals(call.getArgument(0)))
                    .map(s -> s.role(s.roleRowId)).findFirst());
            when(identities.findById(any())).thenAnswer(call -> sources.stream()
                    .filter(s -> s.identityRowPresent && s.identityId.equals(call.getArgument(0)))
                    .map(s -> s.identity(s.identityRowId)).findFirst());
            when(assessments.findById(any())).thenAnswer(call -> sources.stream()
                    .filter(s -> s.assessmentId.equals(call.getArgument(0))).map(Source::assessment).findFirst());
            when(extractions.findBySourceFileIdAndParseResultId(any(), any(), any())).thenAnswer(call -> sources.stream()
                    .filter(s -> PROJECT.equals(call.getArgument(0)) && s.id.equals(call.getArgument(1))
                            && s.parseId.equals(call.getArgument(2))).findFirst()
                    .map(s -> List.copyOf(s.facts)).orElseThrow());
            when(supports.findCurrentByProjectIdAndCandidateId(PROJECT, CANDIDATE)).thenAnswer(call -> rows);
            validator = new CandidateSupportAuthorityValidator(projects, files, parses, roles, identities,
                    new EffectiveDocumentRoleResolver(parses, roles),
                    new EffectiveProjectIdentityResolver(parses, assessments, identities), extractions, supports);
        }
    }

    private static final class Source {
        final UUID id;
        UUID fileProject = PROJECT, parseProject = PROJECT, parseFile, roleProject = PROJECT, roleFile;
        UUID identityProject = PROJECT, identityFile;
        UUID parseId = UUID.randomUUID(), roleId = UUID.randomUUID(), identityId = UUID.randomUUID();
        UUID roleRowId = roleId, identityRowId = identityId;
        final UUID assessmentId = UUID.randomUUID();
        String hash = "a".repeat(64), parseHash = hash, roleHash = hash, identityHash = hash, parseText = TEXT;
        int parseVersion = 1, roleParseVersion = 1, identityParseVersion = 1;
        long identityProjectVersion = 1;
        boolean visible = true, parsePresent = true, rolePresent = true, roleRowPresent = true;
        boolean identityPresent = true, identityRowPresent = true;
        DocumentRole role = DocumentRole.AWARD_NOTICE;
        ProjectIdentityResolution resolution = ProjectIdentityResolution.CONFIRM_SAME_PROJECT;
        ProjectFileIngestionStatus ingestion = ProjectFileIngestionStatus.AVAILABLE;
        ProjectFileParseStatus fileStatus = ProjectFileParseStatus.PARSED, parseStatus = ProjectFileParseStatus.PARSED;
        ProjectFileParseOrigin origin = ProjectFileParseOrigin.CURRENT_PIPELINE;
        final List<ExtractionResult> facts = new ArrayList<>();

        Source(UUID id) { this.id = id; parseFile = roleFile = identityFile = id; resetFacts(); }
        void resetFacts() {
            facts.clear();
            facts.add(fact("AWARD_AMOUNT", "a", new BigDecimal("98.00")));
            facts.add(fact("AWARD_SUPPLIER", "b", null));
            facts.add(fact("AWARD_SUPPLIER_CREDIT_CODE", "c", null));
        }
        ExtractionResult fact(String type, String key, BigDecimal amount) {
            return new ExtractionResult(UUID.randomUUID(), PROJECT, id, parseId, null, key.repeat(64),
                    "extract-v1", type, null, VALUE, VALUE, amount, EXCERPT, 1, 1, "page:1", NOW);
        }
        ProjectFile file() {
            return new ProjectFile(id, fileProject, null, "synthetic.pdf", "synthetic.pdf", "application/pdf",
                    ProjectFileCategory.AWARD_NOTICE, ingestion, fileStatus, "d".repeat(64), hash, 100,
                    TEXT, null, NOW, NOW);
        }
        ProjectFileParseResult parse() {
            return new ProjectFileParseResult(parseId, parseProject, parseFile, parseHash, parseVersion,
                    origin, parseStatus, ProjectFileParseMethod.PDF_TEXT, "TEST", null,
                    parseStatus == ProjectFileParseStatus.PARSED ? parseText : null,
                    parseStatus == ProjectFileParseStatus.PARSED ? sha(parseText) : null,
                    1, 1L, null, null, NOW, NOW, 0);
        }
        ProjectFileRoleDecision role(UUID decisionId) {
            return new ProjectFileRoleDecision(decisionId, roleProject, roleFile, roleHash, parseId,
                    roleParseVersion, assessmentId, role, RoleDecisionStatus.CONFIRMED,
                    "synthetic-user", NOW, null, null, 1);
        }
        ProjectFileIdentityDecision identity(UUID decisionId) {
            return new ProjectFileIdentityDecision(decisionId, identityProject, identityProjectVersion,
                    identityFile, identityHash, parseId, identityParseVersion, assessmentId,
                    resolution, "synthetic-user", NOW, null, null, 1);
        }
        ProjectFileIdentityAssessment assessment() {
            return new ProjectFileIdentityAssessment(assessmentId, PROJECT, identityProjectVersion, id,
                    hash, parseId, parseVersion, ProjectIdentityAssessmentSource.DETERMINISTIC,
                    "test-v1", "human-v1", ProjectIdentityAssessmentStatus.LIKELY_SAME_PROJECT,
                    "e".repeat(64), null, NOW, NOW, 0);
        }
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
}
