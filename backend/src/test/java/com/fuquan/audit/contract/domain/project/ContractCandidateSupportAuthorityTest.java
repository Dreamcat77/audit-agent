package com.fuquan.audit.contract.domain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractCandidateSupportAuthorityTest {
    private static final UUID SUPPORT_AUTHORITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CANDIDATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID SOURCE_FILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID PARSE_RESULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID ROLE_DECISION_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final UUID IDENTITY_DECISION_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final String FILE_SHA256 = "a".repeat(64);
    private static final String FACT_FINGERPRINT = "b".repeat(64);
    private static final Instant CREATED_AT = Instant.parse("2026-09-20T00:00:00Z");

    @Test
    void requiresEveryDurableAttribute() {
        assertThatNullPointerException().isThrownBy(() -> authority(null, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, 1, 1, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 1, "auditor-1", CREATED_AT));
        assertThatNullPointerException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, null, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, 1, 1, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 1, "auditor-1", CREATED_AT));
        assertThatNullPointerException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, null, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, 1, 1, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 1, "auditor-1", CREATED_AT));
        assertThatNullPointerException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, null, FILE_SHA256, PARSE_RESULT_ID, 1, 1, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 1, "auditor-1", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, null, PARSE_RESULT_ID, 1, 1, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 1, "auditor-1", CREATED_AT));
        assertThatNullPointerException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256, null, 1, 1, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 1, "auditor-1", CREATED_AT));
        assertThatNullPointerException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, 1, 1, null, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 1, "auditor-1", CREATED_AT));
        assertThatNullPointerException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, 1, 1, DocumentRole.AWARD_NOTICE, null, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 1, "auditor-1", CREATED_AT));
        assertThatNullPointerException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, 1, 1, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, null, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 1, "auditor-1", CREATED_AT));
        assertThatNullPointerException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, 1, 1, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, null, FACT_FINGERPRINT, 1, "auditor-1", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, 1, 1, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, null, 1, "auditor-1", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, 1, 1, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 1, " ", CREATED_AT));
        assertThatNullPointerException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, 1, 1, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 1, "auditor-1", null));
    }

    @Test
    void acceptsOnlyAwardNoticeSameProjectAndPositiveVersionedLowercaseAuthority() {
        ContractCandidateSupportAuthority authority = authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, 1, 2, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 3, "auditor-1", CREATED_AT);
        assertThat(authority.effectiveRole()).isEqualTo(DocumentRole.AWARD_NOTICE);
        assertThat(authority.identityResolution()).isEqualTo(ProjectIdentityResolution.CONFIRM_SAME_PROJECT);
        assertThatIllegalArgumentException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, 0, 1, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 1, "auditor-1", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, 1, 0, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 1, "auditor-1", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, 1, 1, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 0, "auditor-1", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256.toUpperCase(), PARSE_RESULT_ID, 1, 1, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 1, "auditor-1", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, 1, 1, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT.toUpperCase(), 1, "auditor-1", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, 1, 1, DocumentRole.CONTRACT_BODY, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 1, "auditor-1", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> authority(SUPPORT_AUTHORITY_ID, PROJECT_ID, CANDIDATE_ID, SOURCE_FILE_ID, FILE_SHA256, PARSE_RESULT_ID, 1, 1, DocumentRole.AWARD_NOTICE, ROLE_DECISION_ID, ProjectIdentityResolution.CONFIRM_NOT_SAME_PROJECT, IDENTITY_DECISION_ID, FACT_FINGERPRINT, 1, "auditor-1", CREATED_AT));
    }

    private static ContractCandidateSupportAuthority authority(UUID supportAuthorityId, UUID projectId, UUID candidateId, UUID sourceFileId, String fileSha256, UUID parseResultId, int parseVersion, long projectVersionNo, DocumentRole effectiveRole, UUID roleDecisionId, ProjectIdentityResolution identityResolution, UUID identityDecisionId, String sourceFactFingerprint, long authorityVersion, String createdBy, Instant createdAt) {
        return new ContractCandidateSupportAuthority(supportAuthorityId, projectId, candidateId, sourceFileId, fileSha256, parseResultId, parseVersion, projectVersionNo, effectiveRole, roleDecisionId, identityResolution, identityDecisionId, sourceFactFingerprint, authorityVersion, createdBy, createdAt);
    }
}
