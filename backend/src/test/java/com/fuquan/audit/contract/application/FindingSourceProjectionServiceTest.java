package com.fuquan.audit.contract.application;

import com.fuquan.audit.contract.application.project.CandidateSupportAuthorityValidator;
import com.fuquan.audit.contract.application.project.CandidateSupportAuthorityReadService;
import com.fuquan.audit.contract.application.project.ProjectApplicationService;
import com.fuquan.audit.contract.application.project.ResolvedCandidateSupportAuthority;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentityResolver;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResolver;
import com.fuquan.audit.contract.application.project.port.ContractCandidateSupportAuthorityRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityDecisionRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileIdentityAssessmentRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.domain.AwardNoticeReference;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractLineItem;
import com.fuquan.audit.contract.domain.ContractLineItemBasis;
import com.fuquan.audit.contract.domain.ContractReviewContext;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.contract.domain.Money;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateLineItem;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectContract;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ContractCandidateSupportAuthority;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseMethod;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import com.fuquan.audit.contract.domain.project.RoleDecisionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityDecision;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityAssessment;
import com.fuquan.audit.contract.domain.project.ProjectIdentityResolution;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentSource;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentStatus;
import com.fuquan.audit.contract.domain.rule.AwardSupplierMatchRule;
import com.fuquan.audit.contract.infra.memory.PromotionContractFixtures;
import com.fuquan.audit.contract.infra.memory.project.InMemoryContractCandidateSupportAuthorityRepository;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FindingSourceProjectionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void primaryOnlyHistoricalAndClearedCohortsKeepLegacyFallbackWithoutGrantingAuthority(boolean cleared) {
        AuthorityFixture f = new AuthorityFixture();
        if (cleared) f.assign(List.of());
        ContractCandidate before = f.current;
        ExtractionResult anchor = f.base.awardEvidence("AWARD_AMOUNT", null, null, new BigDecimal("100.0"));
        f.projectEvidence.add(anchor);
        SupportSource humanConfirmedOther = f.source("70000000-0000-0000-0000-000000000001", "100");
        f.projectEvidence.addAll(humanConfirmedOther.facts);
        var candidateReader = new ProjectApplicationService(f.projects, f.base.files, f.base.candidates,
                f.base.extractions, f.base.associations, id -> Optional.empty(),
                new CandidateSupportAuthorityReadService(f.supportRepository, f.base.files, f.parses,
                        f.base.extractions, f.validator), java.time.Clock.systemUTC());
        var detailBefore = candidateReader.candidate(f.base.projectId, f.current.id());

        FindingSourceProjection projected = f.project(F02RuleCodes.AWARD_AMOUNT_MATCH);

        assertThat(projected.available()).isTrue();
        assertThat(projected.sources()).extracting(FindingSourceProjection.Source::fileId)
                .containsExactly(f.base.fileId, f.base.awardFileId);
        assertThat(f.current).isSameAs(before);
        assertThat(f.current.awardAmount()).isEqualByComparingTo("100");
        assertThat(f.current.promotedConfirmationId()).isEqualTo(before.promotedConfirmationId());
        var detailAfter = candidateReader.candidate(f.base.projectId, f.current.id());
        assertThat(detailAfter).isEqualTo(detailBefore);
        assertThat(detailAfter.fieldStates()).containsEntry("AWARD_AMOUNT", "MISSING");
        assertThat(f.supports.findCurrentByProjectIdAndCandidateId(f.base.projectId, f.current.id())).isEmpty();
        assertThat(mockingDetails(f.base.candidates).getInvocations())
                .allMatch(call -> call.getMethod().getName().startsWith("find"));
        verify(f.base.extractions).findByProjectId(f.base.projectId);
        verify(f.base.extractions, never()).findBySourceFileIdAndParseResultId(any(), any(), any());

        f.projectEvidence.add(f.base.awardEvidence("AWARD_AMOUNT", null, null, new BigDecimal("100.00")));
        assertThat(f.project(F02RuleCodes.AWARD_AMOUNT_MATCH).reason()).isEqualTo("SOURCE_AMBIGUOUS");
    }

    @Test
    void sameCandidateSwitchesFromLegacyCategoryFallbackToExactAssignedHumanAuthority() {
        AuthorityFixture f = new AuthorityFixture();
        f.projectEvidence.add(f.base.awardEvidence("AWARD_AMOUNT", null, null, new BigDecimal("100")));
        assertThat(f.project(F02RuleCodes.AWARD_AMOUNT_MATCH).sources())
                .extracting(FindingSourceProjection.Source::fileId)
                .containsExactly(f.base.fileId, f.base.awardFileId);
        UUID candidateId = f.current.id();
        SupportSource assigned = f.source("70000000-0000-0000-0000-000000000001", "100.00");
        // The immutable classifier category is deliberately OTHER; current human authority wins.
        f.assign(List.of(assigned));
        when(f.base.extractions.findByProjectId(f.base.projectId))
                .thenThrow(new AssertionError("explicit award evidence must not scan the project"));

        FindingSourceProjection projected = f.project(F02RuleCodes.AWARD_AMOUNT_MATCH);

        assertThat(projected.available()).isTrue();
        assertThat(projected.sources()).extracting(FindingSourceProjection.Source::fileId)
                .containsExactly(f.base.fileId, assigned.fileId);
        assertThat(projected.sources().get(1).excerpt()).isEqualTo("Synthetic award 100.00");
        assertThat(f.current.id()).isEqualTo(candidateId);
        assertThat(assigned.facts).allMatch(fact -> fact.candidateId() == null);
    }

    @Test
    void explicitModeIgnoresUnassignedOtherCandidateClassifierAndOldParseMatches() {
        AuthorityFixture f = new AuthorityFixture();
        SupportSource assigned = f.source("70000000-0000-0000-0000-000000000001", "99");
        SupportSource other = f.source("f0000000-0000-0000-0000-000000000002", "100");
        var otherAuthority = f.validator.resolveRequested(f.base.projectId, List.of(other.fileId)).supports().get(0);
        UUID otherCandidate = UUID.randomUUID();
        f.supports.replaceCurrentCohort(f.base.projectId, otherCandidate,
                List.of(f.row(otherAuthority, otherCandidate)));
        f.assign(List.of(assigned));
        f.projectEvidence.add(f.base.awardEvidence("AWARD_AMOUNT", null, null, new BigDecimal("100")));
        f.projectEvidence.addAll(other.facts);
        // A matching historic extraction for the assigned file must not substitute for its current parse.
        f.projectEvidence.add(assigned.fact(UUID.randomUUID(), "AWARD_AMOUNT", null, new BigDecimal("100")));

        FindingSourceProjection projected = f.project(F02RuleCodes.AWARD_AMOUNT_MATCH);

        assertThat(projected.available()).isFalse();
        assertThat(projected.reason()).isEqualTo("SOURCE_NOT_FOUND");
        assertThat(projected.sources()).isEmpty();
        verify(f.base.extractions, never()).findByProjectId(any());
    }

    enum StaleSupport { PARSE, ROLE, IDENTITY, CLASSIFIER_ONLY, COHORT_MISSING, COHORT_VERSION,
        COHORT_OWNER, COHORT_FINGERPRINT }

    @ParameterizedTest
    @EnumSource(StaleSupport.class)
    void staleExplicitAuthorityIsSafelyUnavailableWithoutLegacyFallback(StaleSupport stale) {
        AuthorityFixture f = new AuthorityFixture();
        SupportSource assigned = f.source("70000000-0000-0000-0000-000000000001", "100");
        f.assign(List.of(assigned));
        f.projectEvidence.add(f.base.awardEvidence("AWARD_AMOUNT", null, null, new BigDecimal("100")));
        switch (stale) {
            case PARSE -> when(f.parses.findLatestByFileId(assigned.fileId)).thenReturn(Optional.empty());
            case ROLE -> assigned.roleId = UUID.randomUUID();
            case IDENTITY -> assigned.identityId = UUID.randomUUID();
            case CLASSIFIER_ONLY -> {
                when(f.base.files.findByProjectIdAndId(f.base.projectId, assigned.fileId))
                        .thenReturn(Optional.of(assigned.file(ProjectFileCategory.AWARD_NOTICE)));
                when(f.roles.findLatestByFileId(assigned.fileId)).thenReturn(Optional.empty());
            }
            case COHORT_MISSING -> f.supports.deleteCurrentByProjectIdAndCandidateId(f.base.projectId, f.current.id());
            case COHORT_VERSION -> f.current = f.candidate(2L, f.current.supportAuthorityFingerprint(), f.current.awardAmount());
            case COHORT_OWNER -> {
                var row = f.supports.findCurrentByProjectIdAndCandidateId(f.base.projectId, f.current.id()).get(0);
                when(f.supportRepository.findCurrentByProjectIdAndCandidateId(f.base.projectId, f.current.id()))
                        .thenReturn(List.of(new ContractCandidateSupportAuthority(row.supportAuthorityId(),
                                row.projectId(), UUID.randomUUID(), row.sourceFileId(), row.fileSha256(),
                                row.parseResultId(), row.parseVersion(), row.projectVersionNo(), row.effectiveRole(),
                                row.roleDecisionId(), row.identityResolution(), row.identityDecisionId(),
                                row.sourceFactFingerprint(), row.authorityVersion(), row.createdBy(), row.createdAt())));
            }
            case COHORT_FINGERPRINT -> f.current = f.candidate(1L, "f".repeat(64), f.current.awardAmount());
        }

        FindingSourceProjection projected = f.project(F02RuleCodes.AWARD_AMOUNT_MATCH);

        assertThat(projected.available()).isFalse();
        assertThat(projected.reason()).isEqualTo("SUPPORT_AUTHORITY_NOT_CURRENT");
        assertThat(projected.sources()).isEmpty();
        verify(f.base.extractions, never()).findByProjectId(any());
    }

    @Test
    void equalAssignedSourcesRemainVisibleInDeterministicOrderForBothAwardRules() {
        AuthorityFixture f = new AuthorityFixture();
        SupportSource later = f.source("f0000000-0000-0000-0000-000000000002", "100.00");
        SupportSource earlier = f.source("70000000-0000-0000-0000-000000000001", "100.0");
        f.assign(List.of(later, earlier));

        for (String rule : List.of(F02RuleCodes.AWARD_AMOUNT_MATCH, F02RuleCodes.AWARD_SUPPLIER_MATCH)) {
            AuditCheckResult finding = f.base.finding(rule, null);
            FindingSourceProjection projected = f.service.project(f.base.contract, finding);
            assertThat(projected.available()).isTrue();
            assertThat(projected.sources()).extracting(FindingSourceProjection.Source::fileId)
                    .containsExactly(f.base.fileId, earlier.fileId, later.fileId);
            assertThat(projected.sources()).extracting(FindingSourceProjection.Source::role)
                    .containsExactly("CONTRACT", "AWARD_NOTICE", "AWARD_NOTICE");
            assertThat(finding.checkStatus()).isEqualTo(CheckStatus.FAIL);
            assertThat(finding.actualValue()).isEqualTo("actual");
            assertThat(finding.expectedValue()).isEqualTo("expected");
        }
        verify(f.base.extractions, never()).findByProjectId(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unresolvedExplicitAwardAmountNeverSelectsAHumanConfirmedWinner(boolean conflicting) {
        AuthorityFixture f = new AuthorityFixture();
        SupportSource first = f.source("70000000-0000-0000-0000-000000000001", "100");
        SupportSource second = f.source("f0000000-0000-0000-0000-000000000002", "99");
        if (!conflicting) first.facts.removeIf(fact -> fact.factType().equals("AWARD_AMOUNT"));
        f.assign(conflicting ? List.of(first, second) : List.of(first));
        f.current = f.candidate(1L, f.current.supportAuthorityFingerprint(), null);

        FindingSourceProjection projected = f.project(F02RuleCodes.AWARD_AMOUNT_MATCH);

        assertThat(projected.available()).isFalse();
        assertThat(projected.reason()).isEqualTo("SUPPORT_AWARD_VALUE_UNRESOLVED");
        assertThat(projected.sources()).isEmpty();
        verify(f.base.extractions, never()).findByProjectId(any());
    }

    @Test
    void staleSupportDoesNotChangePrimaryContractOrLineEvidence() {
        AuthorityFixture f = new AuthorityFixture();
        var line = f.base.contract.lineItems().get(0);
        f.primaryEvidence.add(f.base.contractEvidence("AMOUNT_UPPERCASE", null, f.base.contract.amountInWords(), null));
        f.primaryEvidence.add(f.base.contractEvidence("LINE_ITEM_QUANTITY", line.itemKey(), null, line.quantity()));
        f.primaryEvidence.add(f.base.contractEvidence("LINE_ITEM_UNIT_PRICE", line.itemKey(), null, line.unitPrice()));
        f.primaryEvidence.add(f.base.contractEvidence("LINE_ITEM_DECLARED_AMOUNT", line.itemKey(), null, line.declaredAmount().amount()));
        List<AuditCheckResult> findings = List.of(f.base.finding(F02RuleCodes.AMOUNT_WORDS_MATCH, null),
                f.base.finding(F02RuleCodes.LINE_SUM_MATCH, null),
                f.base.finding(F02RuleCodes.LINE_ITEM_ARITHMETIC, line.itemKey()));
        List<FindingSourceProjection> before = findings.stream().map(finding -> f.service.project(f.base.contract, finding)).toList();
        SupportSource assigned = f.source("70000000-0000-0000-0000-000000000001", "100");
        f.assign(List.of(assigned));
        assigned.roleId = UUID.randomUUID();

        assertThat(findings.stream().map(finding -> f.service.project(f.base.contract, finding)).toList())
                .containsExactlyElementsOf(before).allMatch(FindingSourceProjection::available);
        verify(f.base.extractions, never()).findByProjectId(any());
    }

    @Test
    void incompleteEvidenceReloadInvalidatesEntireExplicitCohort() {
        AuthorityFixture f = new AuthorityFixture();
        SupportSource first = f.source("70000000-0000-0000-0000-000000000001", "100");
        SupportSource second = f.source("f0000000-0000-0000-0000-000000000002", "100");
        f.assign(List.of(first, second));
        when(f.base.extractions.findBySourceFileIdAndParseResultId(f.base.projectId, second.fileId, second.parseId))
                .thenReturn(List.copyOf(second.facts)).thenReturn(List.of());

        assertThat(f.project(F02RuleCodes.AWARD_AMOUNT_MATCH).reason()).isEqualTo("SUPPORT_AUTHORITY_NOT_CURRENT");
        verify(f.base.extractions, never()).findByProjectId(any());
    }

    @Test
    void unknownSupportInfrastructureFailureDoesNotMasqueradeAsMissingOrLegacyEvidence() {
        AuthorityFixture f = new AuthorityFixture();
        SupportSource assigned = f.source("70000000-0000-0000-0000-000000000001", "100");
        f.assign(List.of(assigned));
        when(f.parses.findLatestByFileId(assigned.fileId)).thenThrow(new IllegalStateException("synthetic unavailable"));

        assertThatThrownBy(() -> f.project(F02RuleCodes.AWARD_AMOUNT_MATCH)).isInstanceOf(IllegalStateException.class);
        verify(f.base.extractions, never()).findByProjectId(any());
    }

    @Test
    void projectsOnlyAUniqueExactPromotedSourceAnchor() {
        Fixture fixture = fixture();
        ExtractionResult anchor = fixture.awardEvidence("AWARD_AMOUNT",
                null, null, new BigDecimal("100.0"));
        ExtractionResult contractAmount = fixture.contractEvidence("AMOUNT_NUMERIC",
                null, null, new BigDecimal("100.00"));
        when(fixture.extractions.findByProjectId(fixture.projectId))
                .thenReturn(List.of(contractAmount, anchor));

        FindingSourceProjection source = fixture.service.project(fixture.contract,
                fixture.finding(F02RuleCodes.AWARD_AMOUNT_MATCH, null));

        assertThat(source.available()).isTrue();
        assertThat(source.comparisonMode()).isEqualTo("CROSS_FILE");
        assertThat(source.sources()).extracting(FindingSourceProjection.Source::role)
                .containsExactly("CONTRACT", "AWARD_NOTICE");
        assertThat(source.sources()).extracting(FindingSourceProjection.Source::fileId)
                .containsExactly(fixture.fileId, fixture.awardFileId);
        assertThat(source.sources()).extracting(FindingSourceProjection.Source::fileName)
                .containsExactly("contract.pdf", "award.pdf");
        assertThat(source.sources().get(1).pageNumber()).isEqualTo(1);
        assertThat(source.sources().get(1).locator()).isEqualTo("pdf:page:1");
        assertThat(source.sources().get(1).excerpt()).isEqualTo("Winning Amount: 100.00");
    }

    @Test
    void failsClosedForChangedSnapshotAmbiguousEvidenceAndUnknownRules() {
        Fixture fixture = fixture();
        Contract changed = new Contract(fixture.contract.id(), fixture.contract.contractNo(),
                fixture.contract.contractName(), fixture.contract.contractType(), fixture.contract.partyA(),
                fixture.contract.partyB(), fixture.contract.partyBCreditCode(), fixture.contract.contractAmount(),
                fixture.contract.amountInWords(), fixture.contract.status(), 1, fixture.contract.createdAt(), NOW,
                fixture.contract.lineItems(), fixture.contract.awardNotice());
        assertThat(fixture.service.project(changed, fixture.finding(F02RuleCodes.AWARD_AMOUNT_MATCH, null)).reason())
                .isEqualTo("CONTRACT_VERSION_NOT_PROMOTION_SNAPSHOT");

        ExtractionResult first = fixture.awardEvidence("AWARD_AMOUNT",
                null, null, new BigDecimal("100.00"));
        ExtractionResult second = fixture.awardEvidence("AWARD_AMOUNT",
                null, null, new BigDecimal("100.00"));
        ExtractionResult contractAmount = fixture.contractEvidence("AMOUNT_NUMERIC",
                null, null, new BigDecimal("100.00"));
        when(fixture.extractions.findByProjectId(fixture.projectId))
                .thenReturn(List.of(contractAmount, first, second));
        assertThat(fixture.service.project(fixture.contract,
                fixture.finding(F02RuleCodes.AWARD_AMOUNT_MATCH, null)).reason()).isEqualTo("SOURCE_AMBIGUOUS");
        assertThat(fixture.service.project(fixture.contract,
                fixture.finding("F02_UNKNOWN", null)).reason()).isEqualTo("RULE_NOT_PROJECTABLE");
    }

    @Test
    void failsClosedWhenAnyInputToAProjectedFindingWasEditedDuringConfirmation() {
        Fixture fixture = fixture();
        ExtractionResult uppercase = fixture.contractEvidence(
                "AMOUNT_UPPERCASE", null, fixture.contract.amountInWords(), null);
        when(fixture.extractions.findByCandidateId(fixture.projectId, fixture.candidate.id()))
                .thenReturn(List.of(uppercase));
        Contract editedAmount = new Contract(fixture.contract.id(), fixture.contract.contractNo(),
                fixture.contract.contractName(), fixture.contract.contractType(), fixture.contract.partyA(),
                fixture.contract.partyB(), fixture.contract.partyBCreditCode(), Money.of(new BigDecimal("101.00")),
                fixture.contract.amountInWords(), fixture.contract.status(), 0, fixture.contract.createdAt(), NOW,
                fixture.contract.lineItems(), fixture.contract.awardNotice());
        assertThat(fixture.service.project(editedAmount,
                fixture.finding(F02RuleCodes.AMOUNT_WORDS_MATCH, null)).reason())
                .isEqualTo("PROMOTION_VALUE_MISMATCH");

        ContractLineItem changedLine = new ContractLineItem(UUID.randomUUID(), fixture.contract.id(), "ITEM-1",
                "Server", new BigDecimal("3"), new BigDecimal("50.00"), Money.of(new BigDecimal("100.00")), 1);
        Contract editedQuantity = new Contract(fixture.contract.id(), fixture.contract.contractNo(),
                fixture.contract.contractName(), fixture.contract.contractType(), fixture.contract.partyA(),
                fixture.contract.partyB(), fixture.contract.partyBCreditCode(), fixture.contract.contractAmount(),
                fixture.contract.amountInWords(), fixture.contract.status(), 0, fixture.contract.createdAt(), NOW,
                List.of(changedLine), fixture.contract.awardNotice());
        ExtractionResult declared = fixture.contractEvidence(
                "LINE_ITEM_DECLARED_AMOUNT", "ITEM-1", null, new BigDecimal("100.00"));
        when(fixture.extractions.findByCandidateId(fixture.projectId, fixture.candidate.id()))
                .thenReturn(List.of(declared));
        assertThat(fixture.service.project(editedQuantity,
                fixture.finding(F02RuleCodes.LINE_ITEM_ARITHMETIC, "ITEM-1")).reason())
                .isEqualTo("PROMOTION_VALUE_MISMATCH");
    }

    @Test
    void internalRulesExposeOneContractSourceInsteadOfPretendingToCompareTwoFiles() {
        Fixture fixture = fixture();
        ExtractionResult numeric = fixture.contractEvidence("AMOUNT_NUMERIC", null, null,
                new BigDecimal("100.00"));
        ExtractionResult uppercase = fixture.contractEvidence("AMOUNT_UPPERCASE", null,
                fixture.contract.amountInWords(), null);
        when(fixture.extractions.findByCandidateId(fixture.projectId, fixture.candidate.id()))
                .thenReturn(List.of(numeric, uppercase));

        FindingSourceProjection source = fixture.service.project(fixture.contract,
                fixture.finding(F02RuleCodes.AMOUNT_WORDS_MATCH, null));

        assertThat(source.available()).isTrue();
        assertThat(source.comparisonMode()).isEqualTo("INTERNAL");
        assertThat(source.sources()).singleElement().satisfies(value -> {
            assertThat(value.role()).isEqualTo("CONTRACT");
            assertThat(value.fileId()).isEqualTo(fixture.fileId);
        });
    }

    @Test
    void lineSumExposesContractTotalAndEveryProvenDeclaredAmountWithoutCalculatedSources() {
        Fixture fixture = fixture(2);
        ExtractionResult contractAmount = fixture.contractEvidenceAt(
                "AMOUNT_NUMERIC", null, null, new BigDecimal("100.00"),
                "合同金额 100.00", "docx/body/paragraph:1/fact:AMOUNT_NUMERIC/occurrence:1");
        ExtractionResult firstDeclared = fixture.contractEvidenceAt(
                "LINE_ITEM_DECLARED_AMOUNT", "ITEM-1", null, new BigDecimal("100.00"),
                "ITEM-1 Server 2 50.00 100.00",
                "docx/table:1/row:2/cell:5/fact:LINE_ITEM_DECLARED_AMOUNT/occurrence:1");
        ExtractionResult calculatedOnly = fixture.contractEvidenceAt(
                "LINE_ITEM_UNIT_PRICE", "ITEM-2", null, new BigDecimal("25.00"),
                "calculated quantity times unit price 500000",
                "docx/table:1/row:3/cell:4/fact:LINE_ITEM_UNIT_PRICE/occurrence:1");
        ExtractionResult secondDeclared = fixture.contractEvidenceAt(
                "LINE_ITEM_DECLARED_AMOUNT", "ITEM-2", null, new BigDecimal("25.00"),
                "ITEM-2 Support 1 25.00 25.00",
                "docx/table:1/row:3/cell:5/fact:LINE_ITEM_DECLARED_AMOUNT/occurrence:1");
        when(fixture.extractions.findByCandidateId(fixture.projectId, fixture.candidate.id()))
                .thenReturn(List.of(contractAmount, firstDeclared, calculatedOnly, secondDeclared));

        FindingSourceProjection source = fixture.service.project(fixture.contract,
                fixture.finding(F02RuleCodes.LINE_SUM_MATCH, null));

        assertThat(source.available()).isTrue();
        assertThat(source.comparisonMode()).isEqualTo("INTERNAL");
        assertThat(source.sources()).hasSize(3);
        assertThat(source.sources()).extracting(FindingSourceProjection.Source::fileId)
                .containsOnly(fixture.fileId);
        assertThat(source.sources()).extracting(FindingSourceProjection.Source::locator)
                .containsExactly(
                        "docx/body/paragraph:1/fact:AMOUNT_NUMERIC/occurrence:1",
                        "docx/table:1/row:2/cell:5/fact:LINE_ITEM_DECLARED_AMOUNT/occurrence:1",
                        "docx/table:1/row:3/cell:5/fact:LINE_ITEM_DECLARED_AMOUNT/occurrence:1");
        assertThat(source.sources()).extracting(FindingSourceProjection.Source::excerpt)
                .containsExactly(
                        "合同金额 100.00",
                        "ITEM-1 Server 2 50.00 100.00",
                        "ITEM-2 Support 1 25.00 25.00")
                .noneMatch(value -> value.contains("500000"));
    }

    @Test
    void amountOnlyArithmeticProjectsOnlyTheDeclaredAmountSource() {
        Contract base = PromotionContractFixtures.contract(UUID.randomUUID(), "SOURCE-AMOUNT-ONLY");
        ContractLineItem amountOnly = new ContractLineItem(UUID.randomUUID(), base.id(),
                "SERVICE-1", "Service fee", ContractLineItemBasis.AMOUNT_ONLY,
                null, null, Money.of(new BigDecimal("100.00")), 1);
        Contract contract = new Contract(base.id(), base.contractNo(), base.contractName(),
                base.contractType(), base.partyA(), base.partyB(), base.partyBCreditCode(),
                base.contractAmount(), base.amountInWords(), base.status(), base.versionNo(),
                base.createdAt(), base.updatedAt(), List.of(amountOnly), base.awardNotice());
        Fixture fixture = fixture(contract);
        ExtractionResult declared = fixture.contractEvidenceAt(
                "LINE_ITEM_DECLARED_AMOUNT", amountOnly.itemKey(), null,
                amountOnly.declaredAmount().amount(), "Service fee 100.00",
                "docx/table:1/row:2/cell:3/fact:LINE_ITEM_DECLARED_AMOUNT/occurrence:1");
        when(fixture.extractions.findByCandidateId(fixture.projectId, fixture.candidate.id()))
                .thenReturn(List.of(declared));

        FindingSourceProjection projection = fixture.service.project(contract,
                fixture.finding(F02RuleCodes.LINE_ITEM_ARITHMETIC, amountOnly.itemKey()));

        assertThat(projection.available()).isTrue();
        assertThat(projection.sources()).singleElement().satisfies(source -> {
            assertThat(source.excerpt()).isEqualTo("Service fee 100.00");
            assertThat(source.locator()).contains("LINE_ITEM_DECLARED_AMOUNT");
            assertThat(source.locator()).doesNotContain("LINE_ITEM_QUANTITY", "LINE_ITEM_UNIT_PRICE");
        });
    }

    @Test
    void legacyCellOnlyPm44RowsReconstructUniqueRowContextAndKeepDeclaredCellLocator() {
        Fixture fixture = pm44Fixture();
        List<ExtractionResult> evidence = fixture.pm44LegacyEvidence();
        when(fixture.extractions.findByCandidateId(fixture.projectId, fixture.candidate.id()))
                .thenReturn(evidence);
        Map<String, String> expectedRows = Map.of(
                "SOFT-01", "SOFT-01 智慧维修系统软件许可 1 40000.00 40000.00",
                "SERV-01", "SERV-01 驻场运维服务 1 17000.00 17000.00",
                "TRAIN-01", "TRAIN-01 系统培训服务 1 10000.00 10000.00");
        String parserShapedFullText = """
                序号 名称 数量 单价 申报金额
                SOFT-01 智慧维修系统软件许可 1 40000.00 40000.00
                SERV-01 驻场运维服务 1 17000.00 17000.00
                TRAIN-01 系统培训服务 1 10000.00 10000.00
                """;

        for (int index = 0; index < fixture.contract.lineItems().size(); index++) {
            ContractLineItem line = fixture.contract.lineItems().get(index);
            int rowNumber = index + 2;
            FindingSourceProjection projection = fixture.service.project(fixture.contract,
                    fixture.finding(F02RuleCodes.LINE_ITEM_ARITHMETIC, line.itemKey()));

            assertThat(projection.available()).isTrue();
            assertThat(projection.reason()).isNull();
            assertThat(projection.sources()).singleElement().satisfies(source -> {
                assertThat(source.excerpt()).isEqualTo(expectedRows.get(line.itemKey()));
                assertThat(source.locator()).isEqualTo("docx/table:1/row:" + rowNumber
                        + "/cell:5/fact:LINE_ITEM_DECLARED_AMOUNT/occurrence:1");
                assertThat(normalizedOccurrences(parserShapedFullText, source.excerpt())).isEqualTo(1);
            });
        }
    }

    @Test
    void legacyReconstructionFailsClosedForMissingDuplicateOrWrongRowSibling() {
        Fixture fixture = pm44Fixture();
        List<ExtractionResult> complete = fixture.legacyLineRow(
                "SOFT-01", "智慧维修系统软件许可", "1", "40000.00", "40000.00", 2);

        List<ExtractionResult> missingName = new ArrayList<>(complete);
        missingName.removeIf(fact -> fact.factType().equals("LINE_ITEM_NAME"));
        assertLegacyExcerptFallsBack(fixture, missingName, "40000.00");

        List<ExtractionResult> duplicateName = new ArrayList<>(complete);
        duplicateName.add(complete.stream()
                .filter(fact -> fact.factType().equals("LINE_ITEM_NAME"))
                .findFirst().orElseThrow());
        assertLegacyExcerptFallsBack(fixture, duplicateName, "40000.00");

        List<ExtractionResult> wrongRow = new ArrayList<>(complete);
        wrongRow.removeIf(fact -> fact.factType().equals("LINE_ITEM_NAME"));
        wrongRow.add(fixture.contractEvidenceAt("LINE_ITEM_NAME", "SOFT-01",
                "智慧维修系统软件许可", null, "智慧维修系统软件许可",
                "docx/table:1/row:9/cell:2/fact:LINE_ITEM_NAME/occurrence:1"));
        assertLegacyExcerptFallsBack(fixture, wrongRow, "40000.00");
    }

    @Test
    void legacyReconstructionRejectsForeignIdentityAndNonTypedPrimaryLocator() {
        Fixture fixture = pm44Fixture();
        List<ExtractionResult> complete = fixture.legacyLineRow(
                "SOFT-01", "智慧维修系统软件许可", "1", "40000.00", "40000.00", 2);
        ExtractionResult name = complete.get(1);

        List<ExtractionResult> wrongProject = new ArrayList<>(complete);
        wrongProject.set(1, fixture.withIdentity(name, UUID.randomUUID(),
                name.sourceFileId(), name.candidateId()));
        assertLegacyExcerptFallsBack(fixture, wrongProject, "40000.00");

        List<ExtractionResult> wrongFile = new ArrayList<>(complete);
        wrongFile.set(1, fixture.withIdentity(name, name.projectId(),
                UUID.randomUUID(), name.candidateId()));
        assertLegacyExcerptFallsBack(fixture, wrongFile, "40000.00");

        List<ExtractionResult> wrongCandidate = new ArrayList<>(complete);
        wrongCandidate.set(1, fixture.withIdentity(name, name.projectId(),
                name.sourceFileId(), UUID.randomUUID()));
        assertLegacyExcerptFallsBack(fixture, wrongCandidate, "40000.00");

        List<ExtractionResult> nonTyped = complete.stream()
                .map(fact -> fixture.withLocator(fact, "pdf:page:1"))
                .toList();
        assertLegacyExcerptFallsBack(fixture, nonTyped, "40000.00");
    }

    @Test
    void existingPersistedRowContextRemainsUnchangedWithoutReconstructionDuplication() {
        Fixture fixture = pm44Fixture();
        String rowContext = "SOFT-01 智慧维修系统软件许可 1 40000.00 40000.00";
        List<ExtractionResult> evidence = new ArrayList<>(fixture.legacyLineRow(
                "SOFT-01", "智慧维修系统软件许可", "1", "40000.00", "40000.00", 2));
        ExtractionResult declared = evidence.get(4);
        evidence.set(4, fixture.withSourceText(declared, rowContext));
        when(fixture.extractions.findByCandidateId(fixture.projectId, fixture.candidate.id()))
                .thenReturn(evidence);

        FindingSourceProjection projection = fixture.service.project(fixture.contract,
                fixture.finding(F02RuleCodes.LINE_ITEM_ARITHMETIC, "SOFT-01"));

        assertThat(projection.available()).isTrue();
        assertThat(projection.sources()).singleElement()
                .extracting(FindingSourceProjection.Source::excerpt)
                .isEqualTo(rowContext);
    }

    @Test
    void overlongLegacyRowFallsBackInsteadOfLeftTruncatingAwayDeclaredTarget() {
        Fixture fixture = pm44Fixture();
        String longName = "服务".repeat(120);
        List<ExtractionResult> evidence = fixture.legacyLineRow(
                "SOFT-01", longName, "1", "40000.00", "40000.00", 2);
        when(fixture.extractions.findByCandidateId(fixture.projectId, fixture.candidate.id()))
                .thenReturn(evidence);

        FindingSourceProjection projection = fixture.service.project(fixture.contract,
                fixture.finding(F02RuleCodes.LINE_ITEM_ARITHMETIC, "SOFT-01"));

        assertThat(projection.available()).isTrue();
        assertThat(projection.sources()).singleElement().satisfies(source -> {
            assertThat(source.excerpt()).isEqualTo("40000.00");
            assertThat(source.excerpt()).hasSizeLessThanOrEqualTo(240);
        });
    }

    @Test
    void lineSumEnrichesEveryLegacyDeclaredRowWithoutChangingSourceAuthority() {
        Fixture fixture = pm44Fixture();
        List<ExtractionResult> evidence = fixture.pm44LegacyEvidence();
        when(fixture.extractions.findByCandidateId(fixture.projectId, fixture.candidate.id()))
                .thenReturn(evidence);

        FindingSourceProjection projection = fixture.service.project(fixture.contract,
                fixture.finding(F02RuleCodes.LINE_SUM_MATCH, null));

        assertThat(projection.available()).isTrue();
        assertThat(projection.sources()).hasSize(4);
        assertThat(projection.sources()).extracting(FindingSourceProjection.Source::excerpt)
                .containsExactly(
                        "合同金额 67000.00",
                        "SOFT-01 智慧维修系统软件许可 1 40000.00 40000.00",
                        "SERV-01 驻场运维服务 1 17000.00 17000.00",
                        "TRAIN-01 系统培训服务 1 10000.00 10000.00");
        assertThat(projection.sources()).extracting(FindingSourceProjection.Source::locator)
                .containsExactly(
                        "docx/body/paragraph:1/fact:AMOUNT_NUMERIC/occurrence:1",
                        "docx/table:1/row:2/cell:5/fact:LINE_ITEM_DECLARED_AMOUNT/occurrence:1",
                        "docx/table:1/row:3/cell:5/fact:LINE_ITEM_DECLARED_AMOUNT/occurrence:1",
                        "docx/table:1/row:4/cell:5/fact:LINE_ITEM_DECLARED_AMOUNT/occurrence:1")
                .noneMatch(locator -> locator.contains("LINE_ITEM_UNIT_PRICE"));
    }

    @Test
    void awardSupplierComparisonUsesContractAndAwardNoticeFiles() {
        Fixture fixture = fixture();
        boolean byCreditCode = fixture.contract.partyBCreditCode() != null
                && fixture.contract.awardNotice().winningBidderCreditCode() != null;
        ExtractionResult contractSupplier = fixture.contractEvidence(
                byCreditCode ? "CREDIT_CODE" : "PARTY_B", null,
                byCreditCode ? fixture.contract.partyBCreditCode() : fixture.contract.partyB(), null);
        ExtractionResult awardSupplier = fixture.awardEvidence(
                byCreditCode ? "AWARD_SUPPLIER_CREDIT_CODE" : "AWARD_SUPPLIER", null,
                byCreditCode ? fixture.contract.awardNotice().winningBidderCreditCode()
                        : fixture.contract.awardNotice().winningBidder(), null);
        when(fixture.extractions.findByProjectId(fixture.projectId))
                .thenReturn(List.of(contractSupplier, awardSupplier));

        FindingSourceProjection source = fixture.service.project(fixture.contract,
                fixture.finding(F02RuleCodes.AWARD_SUPPLIER_MATCH, null));

        assertThat(source.available()).isTrue();
        assertThat(source.sources()).extracting(FindingSourceProjection.Source::fileId)
                .containsExactly(fixture.fileId, fixture.awardFileId);
        assertThat(source.sources()).extracting(FindingSourceProjection.Source::role)
                .containsExactly("CONTRACT", "AWARD_NOTICE");
    }

    @Test
    void equivalentWidthSupplierNamesKeepPassingRuleAndExposeOwnedSources() {
        Fixture fixture = noCreditCodeSupplierFixture();
        ExtractionResult contractSupplier = fixture.withSourceText(
                fixture.contractEvidence("PARTY_B", null, "ACME(SH)", null), "ACME(SH)");
        ExtractionResult awardSupplier = fixture.withSourceText(
                fixture.awardEvidence("AWARD_SUPPLIER", null, "Acme(SH)", null), "Acme(SH)");
        ExtractionResult unrelatedSupplier = fixture.withSourceText(
                fixture.contractEvidence("PARTY_B", null, "Other(SH)", null), "Other(SH)");
        when(fixture.extractions.findByProjectId(fixture.projectId))
                .thenReturn(List.of(unrelatedSupplier, contractSupplier, awardSupplier));

        var ruleFinding = new AwardSupplierMatchRule().evaluate(new ContractReviewContext(fixture.contract),
                new ContractRuleConfiguration(F02RuleCodes.AWARD_SUPPLIER_MATCH, "Supplier match", true,
                        RiskLevel.RED, true, 1, null, NOW)).get(0);
        FindingSourceProjection projection = fixture.service.project(fixture.contract,
                fixture.finding(F02RuleCodes.AWARD_SUPPLIER_MATCH, null));

        assertThat(ruleFinding.checkStatus()).isEqualTo(CheckStatus.PASS);
        assertThat(projection.reason()).isNull();
        assertThat(projection.available()).isTrue();
        assertThat(projection.sources()).extracting(FindingSourceProjection.Source::fileId)
                .containsExactly(fixture.fileId, fixture.awardFileId);
        assertThat(projection.sources()).extracting(FindingSourceProjection.Source::role)
                .containsExactly("CONTRACT", "AWARD_NOTICE");
    }

    @Test
    void genuinelyDifferentSupplierExtractionRemainsUnavailable() {
        Fixture fixture = noCreditCodeSupplierFixture();
        when(fixture.extractions.findByProjectId(fixture.projectId))
                .thenReturn(List.of(fixture.withSourceText(
                                fixture.contractEvidence("PARTY_B", null, "Other(SH)", null), "Other(SH)"),
                        fixture.withSourceText(
                                fixture.awardEvidence("AWARD_SUPPLIER", null, "Acme(SH)", null), "Acme(SH)")));

        FindingSourceProjection projection = fixture.service.project(fixture.contract,
                fixture.finding(F02RuleCodes.AWARD_SUPPLIER_MATCH, null));

        assertThat(projection.available()).isFalse();
        assertThat(projection.reason()).isEqualTo("SOURCE_NOT_FOUND");
        assertThat(projection.sources()).isEmpty();
    }

    @Test
    void rejectsAwardAmountSourceLinkedToAnArbitraryCandidate() {
        Fixture fixture = fixture();
        ExtractionResult contractAmount = fixture.contractEvidence("AMOUNT_NUMERIC", null, null,
                new BigDecimal("100.00"));
        ExtractionResult wronglyLinkedAward = fixture.evidence(fixture.awardFileId, UUID.randomUUID(),
                "AWARD_AMOUNT", null, null, new BigDecimal("100.00"));
        when(fixture.extractions.findByProjectId(fixture.projectId))
                .thenReturn(List.of(contractAmount, wronglyLinkedAward));

        assertThat(fixture.service.project(fixture.contract,
                fixture.finding(F02RuleCodes.AWARD_AMOUNT_MATCH, null)).reason())
                .isEqualTo("SOURCE_NOT_FOUND");
    }

    @Test
    void rejectsAwardSupplierSourceLinkedToAnArbitraryCandidate() {
        Fixture fixture = fixture();
        boolean byCreditCode = fixture.contract.partyBCreditCode() != null
                && fixture.contract.awardNotice().winningBidderCreditCode() != null;
        ExtractionResult contractSupplier = fixture.contractEvidence(
                byCreditCode ? "CREDIT_CODE" : "PARTY_B", null,
                byCreditCode ? fixture.contract.partyBCreditCode() : fixture.contract.partyB(), null);
        ExtractionResult wronglyLinkedAward = fixture.evidence(fixture.awardFileId, UUID.randomUUID(),
                byCreditCode ? "AWARD_SUPPLIER_CREDIT_CODE" : "AWARD_SUPPLIER", null,
                byCreditCode ? fixture.contract.awardNotice().winningBidderCreditCode()
                        : fixture.contract.awardNotice().winningBidder(), null);
        when(fixture.extractions.findByProjectId(fixture.projectId))
                .thenReturn(List.of(contractSupplier, wronglyLinkedAward));

        assertThat(fixture.service.project(fixture.contract,
                fixture.finding(F02RuleCodes.AWARD_SUPPLIER_MATCH, null)).reason())
                .isEqualTo("SOURCE_NOT_FOUND");
    }

    private Fixture fixture() {
        return fixture(1);
    }

    private Fixture noCreditCodeSupplierFixture() {
        Contract base = PromotionContractFixtures.contract(UUID.randomUUID(), "SOURCE-NFKC");
        AwardNoticeReference baseAward = base.awardNotice();
        AwardNoticeReference award = new AwardNoticeReference(baseAward.id(), base.id(), baseAward.noticeNo(),
                baseAward.projectName(), "Acme(SH)", null, baseAward.winningAmount(),
                baseAward.winningDate(), baseAward.sourceRef());
        Contract contract = new Contract(base.id(), base.contractNo(), base.contractName(), base.contractType(),
                base.partyA(), "ＡＣＭＥ（ＳＨ）", null, base.contractAmount(), base.amountInWords(),
                base.status(), base.versionNo(), base.createdAt(), base.updatedAt(), base.lineItems(), award);
        return fixture(contract);
    }

    private Fixture fixture(int lineCount) {
        Contract base = PromotionContractFixtures.contract(UUID.randomUUID(), "SOURCE-1");
        List<ContractLineItem> lines = new java.util.ArrayList<>(base.lineItems());
        if (lineCount > 1) {
            lines.add(new ContractLineItem(UUID.randomUUID(), base.id(), "ITEM-2", "Support",
                    new BigDecimal("1"), new BigDecimal("25.00"),
                    Money.of(new BigDecimal("25.00")), 2));
        }
        Contract contract = lineCount == 1 ? base : new Contract(base.id(), base.contractNo(),
                base.contractName(), base.contractType(), base.partyA(), base.partyB(),
                base.partyBCreditCode(), base.contractAmount(), base.amountInWords(), base.status(),
                base.versionNo(), base.createdAt(), base.updatedAt(), List.copyOf(lines), base.awardNotice());
        return fixture(contract);
    }

    private Fixture pm44Fixture() {
        Contract base = PromotionContractFixtures.contract(UUID.randomUUID(), "SOURCE-PM44");
        List<ContractLineItem> lines = List.of(
                new ContractLineItem(UUID.randomUUID(), base.id(), "SOFT-01", "智慧维修系统软件许可",
                        new BigDecimal("1"), new BigDecimal("40000.00"),
                        Money.of(new BigDecimal("40000.00")), 1),
                new ContractLineItem(UUID.randomUUID(), base.id(), "SERV-01", "驻场运维服务",
                        new BigDecimal("1"), new BigDecimal("17000.00"),
                        Money.of(new BigDecimal("17000.00")), 2),
                new ContractLineItem(UUID.randomUUID(), base.id(), "TRAIN-01", "系统培训服务",
                        new BigDecimal("1"), new BigDecimal("10000.00"),
                        Money.of(new BigDecimal("10000.00")), 3));
        Contract contract = new Contract(base.id(), base.contractNo(), base.contractName(), base.contractType(),
                base.partyA(), base.partyB(), base.partyBCreditCode(), Money.of(new BigDecimal("67000.00")),
                base.amountInWords(), base.status(), base.versionNo(), base.createdAt(), base.updatedAt(),
                lines, base.awardNotice());
        return fixture(contract);
    }

    private Fixture fixture(Contract contract) {
        UUID projectId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID awardFileId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        List<ContractCandidateLineItem> candidateLines = contract.lineItems().stream()
                .map(contractLine -> new ContractCandidateLineItem(UUID.randomUUID(), projectId,
                        candidateId, contractLine.itemKey(), contractLine.itemName(), contractLine.quantity(),
                        contractLine.unitPrice(), contractLine.declaredAmount().amount(), contractLine.sortNo()))
                .toList();
        ContractCandidate candidate = ContractCandidate.draft(candidateId, projectId, fileId,
                contract.contractNo(), contract.contractName(), contract.contractType(), contract.partyA(),
                contract.partyB(), contract.partyBCreditCode(), contract.contractAmount().amount(),
                contract.amountInWords(), contract.awardNotice().winningAmount().amount(),
                contract.awardNotice().winningBidder(), contract.awardNotice().winningBidderCreditCode(),
                candidateLines, NOW).promote(contract.id(), "actor", NOW);
        ProjectContractQueryPort associations = mock(ProjectContractQueryPort.class);
        ContractCandidateRepository candidates = mock(ContractCandidateRepository.class);
        ProjectFileRepository files = mock(ProjectFileRepository.class);
        ExtractionResultRepository extractions = mock(ExtractionResultRepository.class);
        when(associations.findByContractId(contract.id())).thenReturn(Optional.of(
                new ProjectContract(projectId, contract.id(), candidateId, "actor", NOW)));
        when(candidates.findByProjectIdAndId(projectId, candidateId)).thenReturn(Optional.of(candidate));
        ProjectFile sourceFile = new ProjectFile(fileId, projectId, null, null, "contract.pdf",
                "application/pdf", ProjectFileCategory.CONTRACT_BODY, ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PARSED, "a".repeat(64), "b".repeat(64), 1, "text", null, NOW, NOW);
        when(files.findByProjectIdAndId(projectId, fileId)).thenReturn(Optional.of(sourceFile));
        ProjectFile awardFile = new ProjectFile(awardFileId, projectId, null, null, "award.pdf",
                "application/pdf", ProjectFileCategory.AWARD_NOTICE, ProjectFileIngestionStatus.AVAILABLE,
                ProjectFileParseStatus.PARSED, "d".repeat(64), "e".repeat(64), 1, "text", null, NOW, NOW);
        when(files.findByProjectIdAndId(projectId, awardFileId)).thenReturn(Optional.of(awardFile));
        return new Fixture(projectId, fileId, awardFileId, contract, candidate, extractions, associations, candidates, files,
                new FindingSourceProjectionService(associations, candidates, files, extractions));
    }

    private final class AuthorityFixture {
        final Fixture base = fixture();
        ContractCandidate current = base.candidate;
        final ProjectRepository projects = mock(ProjectRepository.class);
        final ProjectFileParseResultRepository parses = mock(ProjectFileParseResultRepository.class);
        final ProjectFileRoleDecisionRepository roles = mock(ProjectFileRoleDecisionRepository.class);
        final ProjectFileIdentityDecisionRepository identities = mock(ProjectFileIdentityDecisionRepository.class);
        final ProjectFileIdentityAssessmentRepository assessments = mock(ProjectFileIdentityAssessmentRepository.class);
        final InMemoryContractCandidateSupportAuthorityRepository supports = new InMemoryContractCandidateSupportAuthorityRepository();
        final ContractCandidateSupportAuthorityRepository supportRepository = mock(ContractCandidateSupportAuthorityRepository.class);
        final CandidateSupportAuthorityValidator validator = new CandidateSupportAuthorityValidator(projects, base.files,
                parses, roles, identities, new EffectiveDocumentRoleResolver(parses, roles),
                new EffectiveProjectIdentityResolver(parses, assessments, identities), base.extractions, supportRepository);
        final List<ExtractionResult> primaryEvidence = new ArrayList<>();
        final List<ExtractionResult> projectEvidence = new ArrayList<>();
        final FindingSourceProjectionService service;

        AuthorityFixture() {
            when(projects.findById(base.projectId)).thenReturn(Optional.of(new Project(base.projectId,
                    "Synthetic project", "SYN-1", null, "actor", 1, NOW, NOW)));
            when(base.candidates.findByProjectIdAndId(base.projectId, current.id())).thenAnswer(call -> Optional.of(current));
            when(supportRepository.findCurrentByProjectIdAndCandidateId(base.projectId, current.id()))
                    .thenAnswer(call -> supports.findCurrentByProjectIdAndCandidateId(base.projectId, current.id()));
            primaryEvidence.add(base.contractEvidence("AMOUNT_NUMERIC", null, null, new BigDecimal("100")));
            primaryEvidence.add(base.contractEvidence("CREDIT_CODE", null, base.contract.partyBCreditCode(), null));
            primaryEvidence.add(base.contractEvidence("PARTY_B", null, base.contract.partyB(), null));
            projectEvidence.addAll(primaryEvidence);
            when(base.extractions.findByCandidateId(base.projectId, current.id())).thenAnswer(call -> List.copyOf(primaryEvidence));
            when(base.extractions.findByProjectId(base.projectId)).thenAnswer(call -> List.copyOf(projectEvidence));
            // Exercise constructor injection before and after the additive dependencies exist.
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("associations", base.associations);
            beans.registerSingleton("candidates", base.candidates);
            beans.registerSingleton("files", base.files);
            beans.registerSingleton("extractions", base.extractions);
            beans.registerSingleton("supports", supportRepository);
            beans.registerSingleton("validator", validator);
            service = (FindingSourceProjectionService) beans.createBean(FindingSourceProjectionService.class,
                    DefaultListableBeanFactory.AUTOWIRE_CONSTRUCTOR, false);
        }

        SupportSource source(String id, String amount) {
            SupportSource source = new SupportSource(base.projectId, UUID.fromString(id), amount,
                    base.contract.awardNotice().winningBidder(), base.contract.awardNotice().winningBidderCreditCode());
            when(base.files.findByProjectIdAndId(base.projectId, source.fileId)).thenReturn(Optional.of(source.file()));
            when(parses.findLatestByFileId(source.fileId)).thenReturn(Optional.of(source.parse()));
            when(roles.findLatestByFileId(source.fileId)).thenAnswer(call -> Optional.of(source.role()));
            when(roles.findById(source.roleId)).thenAnswer(call -> Optional.of(source.role()));
            when(identities.findLatestByFileId(source.fileId)).thenAnswer(call -> Optional.of(source.identity()));
            when(identities.findById(source.identityId)).thenAnswer(call -> Optional.of(source.identity()));
            when(assessments.findLatestByFileId(source.fileId)).thenReturn(Optional.of(source.assessment()));
            when(assessments.findById(source.assessmentId)).thenReturn(Optional.of(source.assessment()));
            when(base.extractions.findBySourceFileIdAndParseResultId(base.projectId, source.fileId, source.parseId))
                    .thenAnswer(call -> List.copyOf(source.facts));
            return source;
        }

        void assign(List<SupportSource> sources) {
            var resolved = validator.resolveRequested(base.projectId, sources.stream().map(source -> source.fileId).toList());
            supports.replaceCurrentCohort(base.projectId, current.id(),
                    resolved.supports().stream().map(source -> row(source, current.id())).toList());
            current = candidate(1L, resolved.supportAuthorityFingerprint(), current.awardAmount());
        }

        ContractCandidateSupportAuthority row(ResolvedCandidateSupportAuthority s, UUID candidateId) {
            return new ContractCandidateSupportAuthority(UUID.randomUUID(), s.projectId(), candidateId,
                    s.sourceFileId(), s.fileSha256(), s.parseResultId(), s.parseVersion(), s.projectVersionNo(),
                    s.effectiveRole(), s.roleDecisionId(), s.identityResolution(), s.identityDecisionId(),
                    s.sourceFactFingerprint(), 1, "actor", NOW);
        }

        ContractCandidate candidate(Long supportVersion, String fingerprint, BigDecimal awardAmount) {
            ContractCandidate c = current;
            return new ContractCandidate(c.id(), c.projectId(), c.sourceContractFileId(), c.sourceParseResultId(),
                    c.sourceRoleDecisionId(), c.sourceIdentityDecisionId(), c.sourceAuthoritySource(), c.sourceFactFingerprint(),
                    c.contractNo(), c.contractName(), c.contractType(), c.partyA(), c.partyB(), c.partyBCreditCode(),
                    c.contractAmount(), c.amountInWords(), awardAmount, c.awardSupplier(), c.awardSupplierCreditCode(),
                    c.lineItems(), c.promotionState(), c.versionNo(), c.promotedContractId(), c.promotedConfirmationId(),
                    c.promotedBy(), c.promotedAt(), c.createdAt(), c.updatedAt(), supportVersion, fingerprint);
        }

        FindingSourceProjection project(String rule) {
            return service.project(base.contract, base.finding(rule, null));
        }
    }

    private static final class SupportSource {
        final UUID projectId;
        final UUID fileId;
        final UUID parseId = UUID.randomUUID();
        final UUID assessmentId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        final String amount;
        final List<ExtractionResult> facts = new ArrayList<>();

        SupportSource(UUID projectId, UUID fileId, String amount, String supplier, String creditCode) {
            this.projectId = projectId;
            this.fileId = fileId;
            this.amount = amount;
            facts.add(fact(parseId, "AWARD_AMOUNT", null, new BigDecimal(amount)));
            facts.add(fact(parseId, "AWARD_SUPPLIER", supplier, null));
            if (creditCode != null) facts.add(fact(parseId, "AWARD_SUPPLIER_CREDIT_CODE", creditCode, null));
        }

        ExtractionResult fact(UUID parse, String type, String text, BigDecimal value) {
            return new ExtractionResult(UUID.randomUUID(), projectId, fileId, parse, null,
                    sha(UUID.randomUUID().toString()), "test-v1", type, null, text, text, value,
                    "Synthetic award " + amount, 1, null, "pdf:page:1/fact:" + type, NOW);
        }

        ProjectFile file() {
            return file(ProjectFileCategory.OTHER);
        }

        ProjectFile file(ProjectFileCategory category) {
            return new ProjectFile(fileId, projectId, null, null, "support.pdf", "application/pdf",
                    category, ProjectFileIngestionStatus.AVAILABLE, ProjectFileParseStatus.PARSED,
                    "a".repeat(64), "b".repeat(64), 1, "Synthetic text", null, NOW, NOW);
        }

        ProjectFileParseResult parse() {
            return new ProjectFileParseResult(parseId, projectId, fileId, file().sha256(), 1,
                    ProjectFileParseOrigin.CURRENT_PIPELINE, ProjectFileParseStatus.PARSED,
                    ProjectFileParseMethod.PDF_TEXT, "test", null, file().parsedText(), sha(file().parsedText()),
                    1, 1L, null, null, NOW, NOW, 0);
        }

        ProjectFileRoleDecision role() {
            return new ProjectFileRoleDecision(roleId, projectId, fileId, file().sha256(), parseId, 1,
                    assessmentId, DocumentRole.AWARD_NOTICE, RoleDecisionStatus.CONFIRMED, "actor", NOW, null, null, 1);
        }

        ProjectFileIdentityDecision identity() {
            return new ProjectFileIdentityDecision(identityId, projectId, 1, fileId, file().sha256(), parseId, 1,
                    assessmentId, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, "actor", NOW, null, null, 1);
        }

        ProjectFileIdentityAssessment assessment() {
            return new ProjectFileIdentityAssessment(assessmentId, projectId, 1, fileId, file().sha256(), parseId, 1,
                    ProjectIdentityAssessmentSource.DETERMINISTIC, "test", "test", ProjectIdentityAssessmentStatus.LIKELY_SAME_PROJECT,
                    "c".repeat(64), null, NOW, NOW, 0);
        }

        private static String sha(String value) {
            try {
                return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
        }
    }

    private void assertLegacyExcerptFallsBack(Fixture fixture, List<ExtractionResult> evidence,
                                                String expectedExcerpt) {
        when(fixture.extractions.findByCandidateId(fixture.projectId, fixture.candidate.id()))
                .thenReturn(evidence);
        FindingSourceProjection projection = fixture.service.project(fixture.contract,
                fixture.finding(F02RuleCodes.LINE_ITEM_ARITHMETIC, "SOFT-01"));
        assertThat(projection.available()).isTrue();
        assertThat(projection.sources()).singleElement()
                .extracting(FindingSourceProjection.Source::excerpt)
                .isEqualTo(expectedExcerpt);
    }

    private static int normalizedOccurrences(String source, String excerpt) {
        String normalizedSource = source.replaceAll("\\s+", "");
        String normalizedExcerpt = excerpt.replaceAll("\\s+", "");
        int count = 0;
        for (int offset = normalizedSource.indexOf(normalizedExcerpt); offset >= 0;
             offset = normalizedSource.indexOf(normalizedExcerpt, offset + 1)) {
            count++;
        }
        return count;
    }

    private record Fixture(UUID projectId, UUID fileId, UUID awardFileId, Contract contract, ContractCandidate candidate,
                           ExtractionResultRepository extractions, ProjectContractQueryPort associations,
                           ContractCandidateRepository candidates, ProjectFileRepository files,
                           FindingSourceProjectionService service) {
        AuditCheckResult finding(String ruleCode, String itemKey) {
            return new AuditCheckResult(UUID.randomUUID(), UUID.randomUUID(), ruleCode, itemKey,
                    CheckStatus.FAIL, RiskLevel.RED, true, "actual", "expected", "detail", 1, NOW);
        }
        ExtractionResult contractEvidence(String factType, String groupKey,
                                          String valueText, BigDecimal valueAmount) {
            return evidence(fileId, candidate.id(), factType, groupKey, valueText, valueAmount);
        }
        ExtractionResult awardEvidence(String factType, String groupKey,
                                       String valueText, BigDecimal valueAmount) {
            return evidence(awardFileId, null, factType, groupKey, valueText, valueAmount);
        }
        ExtractionResult contractEvidenceAt(String factType, String groupKey,
                                            String valueText, BigDecimal valueAmount,
                                            String sourceText, String sourceLocator) {
            return new ExtractionResult(UUID.randomUUID(), projectId, fileId, candidate.id(),
                    "c".repeat(64), "v1", factType, groupKey, valueText,
                    valueText, valueAmount, sourceText, null, null, sourceLocator, NOW);
        }
        List<ExtractionResult> legacyLineRow(String key, String name, String quantity,
                                             String unitPrice, String declaredAmount, int row) {
            String rowLocator = "docx/table:1/row:" + row;
            return List.of(
                    contractEvidenceAt("LINE_ITEM_KEY", key, key, null, key,
                            rowLocator + "/cell:1/fact:LINE_ITEM_KEY/occurrence:1"),
                    contractEvidenceAt("LINE_ITEM_NAME", key, name, null, name,
                            rowLocator + "/cell:2/fact:LINE_ITEM_NAME/occurrence:1"),
                    contractEvidenceAt("LINE_ITEM_QUANTITY", key, quantity, new BigDecimal(quantity), quantity,
                            rowLocator + "/cell:3/fact:LINE_ITEM_QUANTITY/occurrence:1"),
                    contractEvidenceAt("LINE_ITEM_UNIT_PRICE", key, unitPrice, new BigDecimal(unitPrice), unitPrice,
                            rowLocator + "/cell:4/fact:LINE_ITEM_UNIT_PRICE/occurrence:1"),
                    contractEvidenceAt("LINE_ITEM_DECLARED_AMOUNT", key, declaredAmount,
                            new BigDecimal(declaredAmount), declaredAmount,
                            rowLocator + "/cell:5/fact:LINE_ITEM_DECLARED_AMOUNT/occurrence:1"));
        }
        List<ExtractionResult> pm44LegacyEvidence() {
            List<ExtractionResult> evidence = new ArrayList<>();
            evidence.add(contractEvidenceAt("AMOUNT_NUMERIC", null, "67000.00",
                    new BigDecimal("67000.00"), "合同金额 67000.00",
                    "docx/body/paragraph:1/fact:AMOUNT_NUMERIC/occurrence:1"));
            evidence.addAll(legacyLineRow("SOFT-01", "智慧维修系统软件许可",
                    "1", "40000.00", "40000.00", 2));
            evidence.addAll(legacyLineRow("SERV-01", "驻场运维服务",
                    "1", "17000.00", "17000.00", 3));
            evidence.addAll(legacyLineRow("TRAIN-01", "系统培训服务",
                    "1", "10000.00", "10000.00", 4));
            return List.copyOf(evidence);
        }
        ExtractionResult withSourceText(ExtractionResult source, String sourceText) {
            return new ExtractionResult(source.id(), source.projectId(), source.sourceFileId(),
                    source.candidateId(), source.extractionKey(), source.extractionVersion(), source.factType(),
                    source.groupKey(), source.valueText(), source.normalizedValue(), source.valueAmount(),
                    sourceText, source.pageNumber(), source.paragraphIndex(), source.sourceLocator(),
                    source.createdAt());
        }
        ExtractionResult withIdentity(ExtractionResult source, UUID projectId,
                                      UUID sourceFileId, UUID candidateId) {
            return new ExtractionResult(source.id(), projectId, sourceFileId, candidateId,
                    source.extractionKey(), source.extractionVersion(), source.factType(), source.groupKey(),
                    source.valueText(), source.normalizedValue(), source.valueAmount(), source.sourceText(),
                    source.pageNumber(), source.paragraphIndex(), source.sourceLocator(), source.createdAt());
        }
        ExtractionResult withLocator(ExtractionResult source, String locator) {
            return new ExtractionResult(source.id(), source.projectId(), source.sourceFileId(),
                    source.candidateId(), source.extractionKey(), source.extractionVersion(), source.factType(),
                    source.groupKey(), source.valueText(), source.normalizedValue(), source.valueAmount(),
                    source.sourceText(), source.pageNumber(), source.paragraphIndex(), locator,
                    source.createdAt());
        }
        private ExtractionResult evidence(UUID sourceFileId, UUID evidenceCandidateId, String factType,
                                          String groupKey, String valueText, BigDecimal valueAmount) {
            return new ExtractionResult(UUID.randomUUID(), projectId, sourceFileId, evidenceCandidateId,
                    "c".repeat(64), "v1", factType, groupKey, valueText,
                    valueText, valueAmount, "Winning Amount: 100.00", 1, null,
                    "pdf:page:1", NOW);
        }
    }
}
