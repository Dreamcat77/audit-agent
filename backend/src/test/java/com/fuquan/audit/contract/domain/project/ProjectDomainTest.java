package com.fuquan.audit.contract.domain.project;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectDomainTest {

    @Test
    void blankProjectUsesUuidBusinessIdentityAndStringActorThenMetadataUsesCas() {
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        Project blank = Project.blank(UUID.fromString("10000000-0000-0000-0000-000000000001"), "user-alpha", now);

        assertThat(blank.name()).isNull();
        assertThat(blank.projectNo()).isNull();
        assertThat(blank.category()).isNull();
        assertThat(blank.createdBy()).isEqualTo("user-alpha");
        assertThat(blank.versionNo()).isZero();
        assertThat(blank.metadataConfirmed()).isFalse();

        Project confirmed = blank.confirmMetadata(0, "  Project A ", null, "Construction", now.plusSeconds(1));
        assertThat(confirmed.name()).isEqualTo("Project A");
        assertThat(confirmed.versionNo()).isEqualTo(1);
        assertThat(confirmed.metadataConfirmed()).isTrue();
        assertThatThrownBy(() -> confirmed.confirmMetadata(0, null, null, null, now.plusSeconds(2)))
                .isInstanceOf(ProjectVersionConflictException.class);
    }

    @Test
    void candidateLifecycleCarriesVersionAndCompletePromotionFactsWithoutCreatingAContract() {
        Instant createdAt = Instant.parse("2026-08-26T00:00:00Z");
        ContractCandidate candidate = ContractCandidate.draft(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "C-1", null, null, null, null, null, new BigDecimal("100.00"),
                null, null, null, null, java.util.List.of(),
                createdAt);
        UUID contractId = UUID.randomUUID();
        Instant promotedAt = createdAt.plusSeconds(1);
        ContractCandidate promoted = candidate.promote(contractId, "actor-string", promotedAt);

        assertThat(candidate.versionNo()).isZero();
        assertThat(candidate.promotedContractId()).isNull();
        assertThat(candidate.promotedBy()).isNull();
        assertThat(candidate.promotedAt()).isNull();
        assertThat(promoted.promotionState()).isEqualTo(CandidatePromotionState.PROMOTED);
        assertThat(promoted.versionNo()).isEqualTo(1);
        assertThat(promoted.promotedContractId()).isEqualTo(contractId);
        assertThat(promoted.promotedBy()).isEqualTo("actor-string");
        assertThat(promoted.promotedAt()).isEqualTo(promotedAt);
        assertThatThrownBy(() -> promoted.promote(UUID.randomUUID(), "other", promotedAt.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void candidateLifecycleStateRequiresAllOrNonePromotionFactsAndNonNegativeVersion() {
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        UUID contractId = UUID.randomUUID();

        assertThatThrownBy(() -> candidate(CandidatePromotionState.DRAFT, 0,
                contractId, null, null, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate(CandidatePromotionState.PROMOTED, 1,
                contractId, "actor", null, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate(CandidatePromotionState.DRAFT, -1,
                null, null, null, now)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ContractCandidate candidate(CandidatePromotionState state, long versionNo,
                                               UUID promotedContractId, String promotedBy,
                                               Instant promotedAt, Instant now) {
        return new ContractCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "C-1", "Contract", "GOODS", "Buyer", "Seller", null,
                new BigDecimal("100.00"), null, null, null, null, java.util.List.of(),
                state, versionNo, promotedContractId, promotedBy, promotedAt, now, now);
    }
}
