package com.fuquan.audit.contract.infra.memory.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateDerivedFacts;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource;
import com.fuquan.audit.contract.domain.project.ContractCandidateLineItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractCandidateDerivedFactsCasRepositoryTest {
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID CANDIDATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final Instant CREATED_AT = Instant.parse("2026-09-20T00:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-20T01:00:00Z");

    @Test
    void replacesDraftDerivedFactsWhilePreservingIdentityPrimaryAuthorityAndCreationFacts() {
        ContractCandidateRepository repository = repositoryWith(draft());

        ContractCandidate replaced = repository.replaceDraftDerivedFactsIfVersionMatches(PROJECT_ID,
                CANDIDATE_ID, 3L, replacementFacts(), 7L, "b".repeat(64), UPDATED_AT);

        assertThat(replaced.id()).isEqualTo(CANDIDATE_ID);
        assertThat(replaced.projectId()).isEqualTo(PROJECT_ID);
        assertThat(replaced.sourceContractFileId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000103"));
        assertThat(replaced.sourceParseResultId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000104"));
        assertThat(replaced.sourceRoleDecisionId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000105"));
        assertThat(replaced.sourceIdentityDecisionId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000106"));
        assertThat(replaced.sourceAuthoritySource()).isEqualTo(ContractCandidateAuthoritySource.HUMAN_CONFIRMATION);
        assertThat(replaced.sourceFactFingerprint()).isEqualTo("a".repeat(64));
        assertThat(replaced.createdAt()).isEqualTo(CREATED_AT);
        assertThat(replaced.promotionState()).isEqualTo(CandidatePromotionState.DRAFT);
        assertThat(replaced.versionNo()).isEqualTo(4L);
        assertThat(replaced.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(replaced.supportAuthorityVersionNo()).isEqualTo(7L);
        assertThat(replaced.supportAuthorityFingerprint()).isEqualTo("b".repeat(64));
        assertThat(replaced.contractNo()).isEqualTo("C-REPLACED");
        assertThat(replaced.contractName()).isEqualTo("Replaced Contract");
        assertThat(replaced.contractType()).isEqualTo("SERVICE");
        assertThat(replaced.partyA()).isEqualTo("New Buyer");
        assertThat(replaced.partyB()).isEqualTo("New Supplier");
        assertThat(replaced.partyBCreditCode()).isEqualTo("91350000REPLACED");
        assertThat(replaced.contractAmount()).isEqualByComparingTo("200.00");
        assertThat(replaced.amountInWords()).isEqualTo("贰佰元整");
        assertThat(replaced.awardAmount()).isEqualByComparingTo("190.00");
        assertThat(replaced.awardSupplier()).isEqualTo("New Award Supplier");
        assertThat(replaced.awardSupplierCreditCode()).isEqualTo("91350000AWARD");
        assertThat(replaced.lineItems()).extracting(ContractCandidateLineItem::itemKey)
                .containsExactly("new-line-a", "new-line-b");
    }

    @Test
    void rejectsWrongVersionWithoutChangingCandidateOrLines() {
        ContractCandidateRepository repository = repositoryWith(draft());

        assertConflict(() -> repository.replaceDraftDerivedFactsIfVersionMatches(PROJECT_ID,
                CANDIDATE_ID, 2L, replacementFacts(), 7L, "b".repeat(64), UPDATED_AT));

        assertThat(repository.findByProjectIdAndId(PROJECT_ID, CANDIDATE_ID).orElseThrow())
                .usingRecursiveComparison().isEqualTo(draft());
    }

    @Test
    void rejectsMissingCandidateWithoutChangingExistingCandidate() {
        ContractCandidateRepository repository = repositoryWith(draft());
        UUID missingCandidateId = UUID.fromString("00000000-0000-0000-0000-000000000199");
        ContractCandidateDerivedFacts missingCandidateFacts = factsWithLines(List.of(lineOwnedBy("missing", 0,
                PROJECT_ID, missingCandidateId)));

        assertThatThrownBy(() -> repository.replaceDraftDerivedFactsIfVersionMatches(PROJECT_ID,
                missingCandidateId, 3L, missingCandidateFacts, 7L,
                "b".repeat(64), UPDATED_AT))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(BusinessErrorCode.NOT_FOUND);

        assertThat(repository.findByProjectIdAndId(PROJECT_ID, CANDIDATE_ID).orElseThrow())
                .usingRecursiveComparison().isEqualTo(draft());
    }

    @Test
    void rejectsPromotedCandidateWithoutChangingCandidateOrLines() {
        ContractCandidate promoted = draft().promote(UUID.fromString("00000000-0000-0000-0000-000000000107"),
                "auditor", Instant.parse("2026-09-20T00:30:00Z"));
        ContractCandidateRepository repository = repositoryWith(promoted);

        assertConflict(() -> repository.replaceDraftDerivedFactsIfVersionMatches(PROJECT_ID,
                CANDIDATE_ID, 4L, replacementFacts(), 7L, "b".repeat(64), UPDATED_AT));

        assertThat(repository.findByProjectIdAndId(PROJECT_ID, CANDIDATE_ID).orElseThrow())
                .usingRecursiveComparison().isEqualTo(promoted);
    }

    @Test
    void rejectsWrongProjectLineBeforeChangingStoredCandidateOrLines() {
        ContractCandidateRepository repository = repositoryWith(draft());
        ContractCandidateDerivedFacts foreignLines = factsWithLines(List.of(lineOwnedBy("wrong-project", 0,
                UUID.fromString("00000000-0000-0000-0000-000000000109"), CANDIDATE_ID)));

        assertThatThrownBy(() -> repository.replaceDraftDerivedFactsIfVersionMatches(PROJECT_ID,
                CANDIDATE_ID, 3L, foreignLines, 7L, "b".repeat(64), UPDATED_AT))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(repository.findByProjectIdAndId(PROJECT_ID, CANDIDATE_ID).orElseThrow())
                .usingRecursiveComparison().isEqualTo(draft());
    }

    @Test
    void rejectsWrongCandidateLineBeforeChangingStoredCandidateOrLines() {
        ContractCandidateRepository repository = repositoryWith(draft());
        ContractCandidateDerivedFacts foreignLines = factsWithLines(List.of(lineOwnedBy("wrong-candidate", 0,
                PROJECT_ID, UUID.fromString("00000000-0000-0000-0000-000000000110"))));

        assertThatThrownBy(() -> repository.replaceDraftDerivedFactsIfVersionMatches(PROJECT_ID,
                CANDIDATE_ID, 3L, foreignLines, 7L, "b".repeat(64), UPDATED_AT))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(repository.findByProjectIdAndId(PROJECT_ID, CANDIDATE_ID).orElseThrow())
                .usingRecursiveComparison().isEqualTo(draft());
    }

    @Test
    void clearsEveryNullableDerivedScalarAndAllLines() {
        ContractCandidateRepository repository = repositoryWith(draft());

        ContractCandidate cleared = repository.replaceDraftDerivedFactsIfVersionMatches(PROJECT_ID,
                CANDIDATE_ID, 3L, nullFacts(), 7L, "b".repeat(64), UPDATED_AT);

        assertThat(cleared.id()).isEqualTo(CANDIDATE_ID);
        assertThat(cleared.projectId()).isEqualTo(PROJECT_ID);
        assertThat(cleared.sourceContractFileId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000103"));
        assertThat(cleared.sourceParseResultId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000104"));
        assertThat(cleared.sourceRoleDecisionId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000105"));
        assertThat(cleared.sourceIdentityDecisionId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000106"));
        assertThat(cleared.sourceAuthoritySource()).isEqualTo(ContractCandidateAuthoritySource.HUMAN_CONFIRMATION);
        assertThat(cleared.sourceFactFingerprint()).isEqualTo("a".repeat(64));
        assertThat(cleared.createdAt()).isEqualTo(CREATED_AT);
        assertThat(cleared.promotionState()).isEqualTo(CandidatePromotionState.DRAFT);
        assertThat(cleared.versionNo()).isEqualTo(4L);
        assertThat(cleared.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(cleared.supportAuthorityVersionNo()).isEqualTo(7L);
        assertThat(cleared.supportAuthorityFingerprint()).isEqualTo("b".repeat(64));
        assertThat(cleared.contractNo()).isNull();
        assertThat(cleared.contractName()).isNull();
        assertThat(cleared.contractType()).isNull();
        assertThat(cleared.partyA()).isNull();
        assertThat(cleared.partyB()).isNull();
        assertThat(cleared.partyBCreditCode()).isNull();
        assertThat(cleared.contractAmount()).isNull();
        assertThat(cleared.amountInWords()).isNull();
        assertThat(cleared.awardAmount()).isNull();
        assertThat(cleared.awardSupplier()).isNull();
        assertThat(cleared.awardSupplierCreditCode()).isNull();
        assertThat(cleared.lineItems()).isEmpty();
    }

    private static ContractCandidateRepository repositoryWith(ContractCandidate candidate) {
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        ContractCandidateRepository repository = repositories.contractCandidateRepository();
        repository.insertIfAbsent(candidate);
        return repository;
    }

    private static ContractCandidate draft() {
        return new ContractCandidate(CANDIDATE_ID, PROJECT_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000103"),
                UUID.fromString("00000000-0000-0000-0000-000000000104"),
                UUID.fromString("00000000-0000-0000-0000-000000000105"),
                UUID.fromString("00000000-0000-0000-0000-000000000106"),
                ContractCandidateAuthoritySource.HUMAN_CONFIRMATION, "a".repeat(64),
                "C-ORIGINAL", "Original Contract", "GOODS", "Original Buyer", "Original Supplier",
                "91350000ORIGINAL", new BigDecimal("100.00"), "壹佰元整", new BigDecimal("90.00"),
                "Original Award Supplier", "91350000ORIGINALAWARD", List.of(line("old-line", 0)),
                CandidatePromotionState.DRAFT, 3L, null, null, null, null, CREATED_AT, CREATED_AT);
    }

    private static ContractCandidateDerivedFacts replacementFacts() {
        return new ContractCandidateDerivedFacts("C-REPLACED", "Replaced Contract", "SERVICE", "New Buyer",
                "New Supplier", "91350000REPLACED", new BigDecimal("200.00"), "贰佰元整",
                new BigDecimal("190.00"), "New Award Supplier", "91350000AWARD",
                List.of(line("new-line-a", 0), line("new-line-b", 1)));
    }

    private static ContractCandidateDerivedFacts nullFacts() {
        return new ContractCandidateDerivedFacts(null, null, null, null, null, null, null, null, null,
                null, null, List.of());
    }

    private static ContractCandidateDerivedFacts factsWithLines(List<ContractCandidateLineItem> lines) {
        return new ContractCandidateDerivedFacts("C-REPLACED", "Replaced Contract", "SERVICE", "New Buyer",
                "New Supplier", "91350000REPLACED", new BigDecimal("200.00"), "贰佰元整",
                new BigDecimal("190.00"), "New Award Supplier", "91350000AWARD", lines);
    }

    private static ContractCandidateLineItem line(String key, int sortNo) {
        return lineOwnedBy(key, sortNo, PROJECT_ID, CANDIDATE_ID);
    }

    private static ContractCandidateLineItem lineOwnedBy(String key, int sortNo, UUID projectId, UUID candidateId) {
        return new ContractCandidateLineItem(UUID.nameUUIDFromBytes(key.getBytes()), projectId, candidateId,
                key, key + " name", new BigDecimal("2"), new BigDecimal("3.00"),
                new BigDecimal("6.00"), sortNo);
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(BusinessErrorCode.CONFLICT);
    }
}
