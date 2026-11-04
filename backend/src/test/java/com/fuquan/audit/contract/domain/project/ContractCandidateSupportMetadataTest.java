package com.fuquan.audit.contract.domain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractCandidateSupportMetadataTest {
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final String FINGERPRINT = "a".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void historicalConstructorsAndFactoriesKeepPrimaryAuthorityAndNullSupportMetadata() {
        UUID id = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID parseId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();

        List<ContractCandidate> candidates = List.of(
                ContractCandidate.draft(id, projectId, fileId, "C-1", "Contract", "GOODS", "Buyer",
                        "Supplier", null, new BigDecimal("100.00"), null, null, null, null, List.of(), NOW),
                ContractCandidate.draftWithAuthority(id, projectId, fileId, parseId, roleId,
                        ContractCandidateAuthoritySource.HUMAN_CONFIRMATION, FINGERPRINT,
                        "C-1", "Contract", "GOODS", "Buyer", "Supplier", null,
                        new BigDecimal("100.00"), null, null, null, null, List.of(), NOW),
                ContractCandidate.draftWithAuthority(id, projectId, fileId, parseId, roleId, identityId,
                        ContractCandidateAuthoritySource.HUMAN_CONFIRMATION, FINGERPRINT,
                        "C-1", "Contract", "GOODS", "Buyer", "Supplier", null,
                        new BigDecimal("100.00"), null, null, null, null, List.of(), NOW),
                new ContractCandidate(id, projectId, fileId, "C-1", "Contract", "GOODS", "Buyer",
                        "Supplier", null, new BigDecimal("100.00"), null, null, null, null, List.of(),
                        CandidatePromotionState.DRAFT, 0, null, null, null, NOW, NOW),
                new ContractCandidate(id, projectId, fileId, parseId, roleId,
                        ContractCandidateAuthoritySource.HUMAN_CONFIRMATION, FINGERPRINT,
                        "C-1", "Contract", "GOODS", "Buyer", "Supplier", null,
                        new BigDecimal("100.00"), null, null, null, null, List.of(),
                        CandidatePromotionState.DRAFT, 0, null, null, null, NOW, NOW),
                new ContractCandidate(id, projectId, fileId, parseId, roleId, identityId,
                        ContractCandidateAuthoritySource.HUMAN_CONFIRMATION, FINGERPRINT,
                        "C-1", "Contract", "GOODS", "Buyer", "Supplier", null,
                        new BigDecimal("100.00"), null, null, null, null, List.of(),
                        CandidatePromotionState.DRAFT, 0, null, null, null, NOW, NOW),
                new ContractCandidate(id, projectId, fileId, parseId, roleId, identityId,
                        ContractCandidateAuthoritySource.HUMAN_CONFIRMATION, FINGERPRINT,
                        "C-1", "Contract", "GOODS", "Buyer", "Supplier", null,
                        new BigDecimal("100.00"), null, null, null, null, List.of(),
                        CandidatePromotionState.DRAFT, 0, null, null, null, null, NOW, NOW));

        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.id()).isEqualTo(id);
            assertThat(candidate.projectId()).isEqualTo(projectId);
            assertThat(candidate.sourceContractFileId()).isEqualTo(fileId);
            assertThat(candidate.supportAuthorityVersionNo()).isNull();
            assertThat(candidate.supportAuthorityFingerprint()).isNull();
        });
        assertPrimaryAuthority(candidates.get(1), parseId, roleId, null);
        assertPrimaryAuthority(candidates.get(2), parseId, roleId, identityId);
        assertPrimaryAuthority(candidates.get(4), parseId, roleId, null);
        assertPrimaryAuthority(candidates.get(5), parseId, roleId, identityId);
        assertPrimaryAuthority(candidates.get(6), parseId, roleId, identityId);
        assertThat(candidates.get(6).promotedConfirmationId()).isNull();
    }

    @Test
    void supportMetadataRequiresACompletePositiveLowercaseSha256Pair() {
        assertThatThrownBy(() -> candidateWithSupport(1L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidateWithSupport(null, FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidateWithSupport(0L, FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidateWithSupport(-1L, FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidateWithSupport(1L, "A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidateWithSupport(1L, "a".repeat(63)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void explicitClearMetadataIsValidWithoutSupportRows() {
        ContractCandidate candidate = candidateWithSupport(1L, FINGERPRINT);

        assertThat(candidate.lineItems()).isEmpty();
        assertThat(candidate.supportAuthorityVersionNo()).isEqualTo(1L);
        assertThat(candidate.supportAuthorityFingerprint()).isEqualTo(FINGERPRINT);
    }

    @Test
    void lifecycleTransitionsPreserveSupportMetadataIncludingPrimaryReauthorization() {
        ContractCandidate candidate = candidateWithSupport(7L, FINGERPRINT);

        ContractCandidate reauthorized = candidate.reauthorize(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "b".repeat(64), NOW.plusSeconds(1));
        assertSupportMetadata(reauthorized);
        assertThat(reauthorized.sourceContractFileId()).isEqualTo(candidate.sourceContractFileId());
        assertSupportMetadata(candidate.confirmed(NOW.plusSeconds(1)));
        assertSupportMetadata(candidate.promote(UUID.randomUUID(), "actor", NOW.plusSeconds(1)));
        assertSupportMetadata(candidate.promote(UUID.randomUUID(), UUID.randomUUID(), "actor", NOW.plusSeconds(1)));
    }

    @Test
    void supportMetadataRemainsInternalToTheExistingCandidateJsonShape() throws Exception {
        ContractCandidate historical = ContractCandidate.draft(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "C-1", "Contract", "GOODS", "Buyer", "Supplier", null,
                new BigDecimal("100.00"), null, null, null, null, List.of(), NOW);

        assertExistingJsonShape(historical);
        assertExistingJsonShape(candidateWithSupport(1L, FINGERPRINT));
    }

    private static void assertExistingJsonShape(ContractCandidate candidate) throws Exception {
        JsonNode json = JSON.readTree(JSON.writeValueAsBytes(candidate));
        Set<String> fields = new HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);

        assertThat(fields).containsExactlyInAnyOrder(
                "id", "projectId", "sourceContractFileId", "sourceParseResultId", "sourceRoleDecisionId",
                "sourceIdentityDecisionId", "sourceAuthoritySource", "sourceFactFingerprint", "contractNo",
                "contractName", "contractType", "partyA", "partyB", "partyBCreditCode", "contractAmount",
                "amountInWords", "awardAmount", "awardSupplier", "awardSupplierCreditCode", "lineItems",
                "promotionState", "versionNo", "promotedContractId", "promotedConfirmationId", "promotedBy",
                "promotedAt", "createdAt", "updatedAt");
        assertThat(json.has("supportAuthorityVersionNo")).isFalse();
        assertThat(json.has("supportAuthorityFingerprint")).isFalse();
    }

    private static void assertSupportMetadata(ContractCandidate candidate) {
        assertThat(candidate.supportAuthorityVersionNo()).isEqualTo(7L);
        assertThat(candidate.supportAuthorityFingerprint()).isEqualTo(FINGERPRINT);
    }

    private static void assertPrimaryAuthority(ContractCandidate candidate, UUID parseId, UUID roleId,
            UUID identityId) {
        assertThat(candidate.sourceParseResultId()).isEqualTo(parseId);
        assertThat(candidate.sourceRoleDecisionId()).isEqualTo(roleId);
        assertThat(candidate.sourceIdentityDecisionId()).isEqualTo(identityId);
        assertThat(candidate.sourceAuthoritySource())
                .isEqualTo(ContractCandidateAuthoritySource.HUMAN_CONFIRMATION);
        assertThat(candidate.sourceFactFingerprint()).isEqualTo(FINGERPRINT);
    }

    private static ContractCandidate candidateWithSupport(Long supportAuthorityVersionNo,
            String supportAuthorityFingerprint) {
        return new ContractCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ContractCandidateAuthoritySource.HUMAN_CONFIRMATION, "b".repeat(64),
                "C-1", "Contract", "GOODS", "Buyer", "Supplier", null,
                new BigDecimal("100.00"), null, null, null, null, List.of(),
                CandidatePromotionState.DRAFT, 0, null, null, null, null, NOW, NOW,
                supportAuthorityVersionNo, supportAuthorityFingerprint);
    }
}
