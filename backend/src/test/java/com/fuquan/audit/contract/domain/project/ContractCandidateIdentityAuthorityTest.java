package com.fuquan.audit.contract.domain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractCandidateIdentityAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");

    @Test
    void newCandidateBindsRoleAndIdentityDecisionsAndDraftOnlyReauthorizesIdentity() {
        UUID project = UUID.randomUUID(), file = UUID.randomUUID(), parse = UUID.randomUUID();
        UUID role = UUID.randomUUID(), identity = UUID.randomUUID(), laterIdentity = UUID.randomUUID();
        var candidate = ContractCandidate.draftWithAuthority(UUID.randomUUID(), project, file, parse, role,
                identity, ContractCandidateAuthoritySource.HUMAN_CONFIRMATION, "a".repeat(64),
                "C-001", "Contract", null, null, null, null, null, null, null, null, null,
                List.of(), NOW);
        assertThat(candidate.sourceIdentityDecisionId()).isEqualTo(identity);
        var updated = candidate.reauthorize(parse, role, laterIdentity, "b".repeat(64), NOW.plusSeconds(1));
        assertThat(updated.id()).isEqualTo(candidate.id());
        assertThat(updated.sourceParseResultId()).isEqualTo(parse);
        assertThat(updated.sourceRoleDecisionId()).isEqualTo(role);
        assertThat(updated.sourceIdentityDecisionId()).isEqualTo(laterIdentity);
        assertThat(updated.contractNo()).isEqualTo("C-001");
        assertThat(updated.versionNo()).isEqualTo(1);
        assertThatThrownBy(() -> updated.promote(UUID.randomUUID(), "actor", NOW.plusSeconds(2))
                .reauthorize(parse, role, identity, "c".repeat(64), NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void historicalNullIdentityProvenanceIsReadableButNotFabricated() {
        var old = ContractCandidate.draftWithAuthority(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), ContractCandidateAuthoritySource.HUMAN_CONFIRMATION,
                "a".repeat(64), "C-001", null, null, null, null, null, null, null, null, null, null,
                List.of(), NOW);
        assertThat(old.sourceIdentityDecisionId()).isNull();
    }

    @Test
    void historicalPromotedRowWithoutConfirmationRemainsReadableWithLegacyProvenance() {
        var historical = ContractCandidate.draft(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "OLD-1", "Old Contract", "GOODS", "Buyer", "Supplier", null,
                null, null, null, null, null, List.of(), NOW)
                .promote(UUID.randomUUID(), "actor", NOW.plusSeconds(1));
        assertThat(historical.promotionState()).isEqualTo(CandidatePromotionState.PROMOTED);
        assertThat(historical.promotedConfirmationId()).isNull();
        assertThat(historical.promotionProvenanceSource()).isEqualTo("LEGACY");
    }
}
