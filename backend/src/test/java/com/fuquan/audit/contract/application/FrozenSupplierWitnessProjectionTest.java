package com.fuquan.audit.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fuquan.audit.contract.application.project.port.*;
import com.fuquan.audit.contract.application.reviewrun.*;
import com.fuquan.audit.contract.domain.*;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.contract.domain.reviewrun.*;
import com.fuquan.audit.contract.infra.memory.LocalContractFixtures;
import com.fuquan.audit.review.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class FrozenSupplierWitnessProjectionTest {
    private static final Instant NOW = Instant.parse("2026-10-19T01:00:00Z");

    @Test void retainsEveryIndependentAnchorInStableOrder() {
        var f = fixture("Supplier-A", "Supplier-A");
        var a = fact(f, f.body, "PARTY_B", "Supplier-A", "signature", 2);
        var b = fact(f, f.body, "PARTY_B", "Supplier-A", "heading", 1);
        f.bodyFacts(List.of(a, b));
        var first = f.project();
        assertThat(first.available()).isTrue();
        assertThat(first.sources()).extracting(FindingSourceProjection.Source::excerpt)
                .containsExactly("heading", "signature", "award");
        f.bodyFacts(List.of(b, a));
        assertThat(f.project()).isEqualTo(first);
    }

    @Test void doesNotChooseTheConvenientMatchingScalar() {
        var f = fixture("Supplier-A", "Supplier-A");
        f.bodyFacts(List.of(fact(f, f.body, "PARTY_B", "Supplier-A", "heading", 1),
                fact(f, f.body, "PARTY_B", "Supplier-B", "signature", 2)));
        assertThat(f.project().reason()).isEqualTo("SOURCE_AMBIGUOUS");
    }

    @Test void contradictoryValuesAtTheSameAnchorCannotCollapse() {
        var f = fixture("Supplier-A", "Supplier-A");
        f.bodyFacts(List.of(fact(f, f.body, "PARTY_B", "Supplier-A", "heading", 1),
                fact(f, f.body, "PARTY_B", "Supplier-B", "heading", 1)));
        assertThat(f.project().reason()).isEqualTo("SOURCE_AMBIGUOUS");
    }

    @Test void differentFrozenSuppliersStillExposeTheirOwnOriginalEvidence() {
        var f = fixture("Supplier-A", "Supplier-B");
        f.bodyFacts(List.of(fact(f, f.body, "PARTY_B", "Supplier-A", "heading", 1),
                fact(f, f.body, "PARTY_B", "Supplier-A", "signature", 2)));
        assertThat(f.project().available()).isTrue();
        assertThat(f.project().sources()).hasSize(3);
    }

    @Test void wrongCandidateAndLateFactsDoNotBecomeWitnesses() {
        var f = fixture("Supplier-A", "Supplier-A");
        var good = fact(f, f.body, "PARTY_B", "Supplier-A", "heading", 1);
        var wrongCandidate = altered(good, UUID.randomUUID(), good.parseResultId(), NOW.minusSeconds(1));
        var late = altered(good, good.candidateId(), good.parseResultId(), NOW.plusSeconds(1));
        f.bodyFacts(List.of(good, wrongCandidate, late));
        assertThat(f.project().available()).isTrue();
        assertThat(f.project().sources()).hasSize(2);
        f.bodyFacts(List.of(wrongCandidate, late));
        assertThat(f.project().available()).isFalse();
    }

    @Test void wrongParseReturnedByRepositoryFailsClosed() {
        var f = fixture("Supplier-A", "Supplier-A");
        var fact = fact(f, f.body, "PARTY_B", "Supplier-A", "heading", 1);
        f.bodyFacts(List.of(altered(fact, fact.candidateId(), UUID.randomUUID(), fact.createdAt())));
        assertThat(f.project().reason()).isEqualTo("FROZEN_PARSE_FACTS_UNVERIFIED");
    }

    @Test void awardFactBoundToAnotherCandidateIsNotAValidWitness() {
        var f = fixture("Supplier-A", "Supplier-A");
        f.bodyFacts(List.of(fact(f, f.body, "PARTY_B", "Supplier-A", "heading", 1)));
        var award = fact(f, f.award, "AWARD_SUPPLIER", "Supplier-A", "award", 1);
        when(f.repo.findBySourceFileIdAndParseResultId(f.run.projectId(), f.award.fileId(), f.award.parseResultId()))
                .thenReturn(List.of(altered(award, UUID.randomUUID(), award.parseResultId(), award.createdAt())));
        assertThat(f.project().available()).isFalse();
    }

    @Test void unrelatedFactTypeDoesNotSupplyMissingParty() {
        var f = fixture("Supplier-A", "Supplier-A");
        f.bodyFacts(List.of(fact(f, f.body, "AWARD_SUPPLIER", "Supplier-A", "heading", 1)));
        assertThat(f.project().available()).isFalse();
    }

    @Test void anotherFrozenContractFileIsNotMergedIntoOneScalarSide() {
        var f = fixture("Supplier-A", "Supplier-A");
        var other = material(3, DocumentRole.CONTRACT_BODY);
        f.bodyFacts(List.of(fact(f, f.body, "PARTY_B", "Supplier-A", "heading", 1)));
        when(f.repo.findBySourceFileIdAndParseResultId(f.run.projectId(), other.fileId(), other.parseResultId()))
                .thenReturn(List.of(fact(f, other, "PARTY_B", "Supplier-A", "signature", 2)));
        var input = new FrozenReviewInput(f.run, f.input.frozenContract(), List.of(f.body, f.award, other));
        assertThat(f.service.projectFrozen(input, f.check).reason()).isEqualTo("SOURCE_AMBIGUOUS");
    }

    @Test void creditCodePrecedenceDoesNotBorrowNameWitnessesOrAllowMultipleCodeAnchors() {
        var f = fixture("Supplier-A", "Supplier-A");
        var withCodes = LocalContractFixtures.contracts().get(0);
        var input = new FrozenReviewInput(new ContractReviewRun(f.run.runId(), f.run.projectId(), withCodes.id(),
                withCodes.versionNo(), f.run.sourceCandidateId(), null, 2, "{}", ContractSnapshotCanonicalizer.sha256("{}"),
                "profile", f.run.ruleVersion(), "models", "b".repeat(64), "actor", ContractReviewRunStatus.RUNNING,
                NOW, NOW, null, null, null, null, 1), withCodes, f.input.materials());
        f.bodyFacts(List.of(fact(f, f.body, "PARTY_B", withCodes.partyB(), "heading", 1)));
        assertThat(f.service.projectFrozen(input, f.check).available()).isFalse();
        f.bodyFacts(List.of(fact(f, f.body, "CREDIT_CODE", withCodes.partyBCreditCode(), "heading", 1),
                fact(f, f.body, "CREDIT_CODE", withCodes.partyBCreditCode(), "signature", 2)));
        when(f.repo.findBySourceFileIdAndParseResultId(f.run.projectId(), f.award.fileId(), f.award.parseResultId()))
                .thenReturn(List.of(fact(f, f.award, "AWARD_SUPPLIER_CREDIT_CODE",
                        withCodes.awardNotice().winningBidderCreditCode(), "award", 1)));
        assertThat(f.service.projectFrozen(input, f.check).reason()).isEqualTo("SOURCE_AMBIGUOUS");
    }

    private static Fixture fixture(String party, String awardSupplier) {
        var old = LocalContractFixtures.contracts().get(0);
        var a = old.awardNotice();
        var award = new AwardNoticeReference(a.id(), a.contractId(), a.noticeNo(), a.projectName(),
                awardSupplier, null, a.winningAmount(), a.winningDate(), a.sourceRef());
        var contract = new Contract(old.id(), old.contractNo(), old.contractName(), old.contractType(),
                old.partyA(), party, null, old.contractAmount(), old.amountInWords(), old.status(),
                old.versionNo(), old.createdAt(), old.updatedAt(), old.lineItems(), award);
        var run = new ContractReviewRun(UUID.randomUUID(), UUID.randomUUID(), contract.id(), contract.versionNo(),
                UUID.randomUUID(), null, 2, "{}", ContractSnapshotCanonicalizer.sha256("{}"), "profile",
                "f02-policy-v2:" + "a".repeat(64), "models", "b".repeat(64), "actor",
                ContractReviewRunStatus.RUNNING, NOW, NOW, null, null, null, null, 1);
        var body = material(1, DocumentRole.CONTRACT_BODY);
        var awardMaterial = material(2, DocumentRole.AWARD_NOTICE);
        var repo = mock(ExtractionResultRepository.class);
        var service = new FindingSourceProjectionService(mock(ProjectContractQueryPort.class),
                mock(ContractCandidateRepository.class), mock(ProjectFileRepository.class), repo);
        var check = new AuditCheckResult(UUID.randomUUID(), UUID.randomUUID(), F02RuleCodes.AWARD_SUPPLIER_MATCH,
                null, party.equals(awardSupplier) ? CheckStatus.PASS : CheckStatus.FAIL,
                RiskLevel.GREEN, false, party, awardSupplier, "supplier comparison", 1, NOW);
        var f = new Fixture(run, body, awardMaterial, repo, service,
                new FrozenReviewInput(run, contract, List.of(body, awardMaterial)), check);
        when(repo.findBySourceFileIdAndParseResultId(run.projectId(), awardMaterial.fileId(), awardMaterial.parseResultId()))
                .thenReturn(List.of(fact(f, awardMaterial, "AWARD_SUPPLIER", awardSupplier, "award", 1)));
        return f;
    }

    private static FrozenReviewMaterial material(int ordinal, DocumentRole role) {
        return new FrozenReviewMaterial(UUID.randomUUID(), ordinal, UUID.randomUUID(), "c".repeat(64),
                UUID.randomUUID(), 1, "d".repeat(64), "heading signature award", role,
                UUID.randomUUID(), UUID.randomUUID(), null, "synthetic.txt");
    }

    private static ExtractionResult fact(Fixture f, FrozenReviewMaterial m, String type, String value,
            String excerpt, int paragraph) {
        return new ExtractionResult(UUID.randomUUID(), f.run.projectId(), m.fileId(), m.parseResultId(),
                m.effectiveRole() == DocumentRole.CONTRACT_BODY ? f.run.sourceCandidateId() : null,
                "e".repeat(64), "test-v1", type, null, value, value, null, excerpt,
                null, paragraph, "docx:paragraph:" + paragraph, NOW.minusSeconds(1));
    }

    private static ExtractionResult altered(ExtractionResult f, UUID candidate, UUID parse, Instant created) {
        return new ExtractionResult(UUID.randomUUID(), f.projectId(), f.sourceFileId(), parse, candidate,
                f.extractionKey(), f.extractionVersion(), f.factType(), f.groupKey(), f.valueText(),
                f.normalizedValue(), f.valueAmount(), f.sourceText(), f.pageNumber(), f.paragraphIndex(),
                f.sourceLocator(), created);
    }

    private record Fixture(ContractReviewRun run, FrozenReviewMaterial body, FrozenReviewMaterial award,
            ExtractionResultRepository repo, FindingSourceProjectionService service, FrozenReviewInput input,
            AuditCheckResult check) {
        void bodyFacts(List<ExtractionResult> values) {
            when(repo.findBySourceFileIdAndParseResultId(run.projectId(), body.fileId(), body.parseResultId()))
                    .thenReturn(values);
        }
        FindingSourceProjection project() { return service.projectFrozen(input, check); }
    }
}
