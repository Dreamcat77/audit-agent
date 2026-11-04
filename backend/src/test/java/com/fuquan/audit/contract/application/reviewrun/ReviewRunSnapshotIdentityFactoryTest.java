package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewRunSnapshotIdentityFactoryTest {
    private final ReviewRunSnapshotIdentityFactory identities = new ReviewRunSnapshotIdentityFactory();
    private final UUID projectId = UUID.randomUUID(), contractId = UUID.randomUUID(), candidateId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-15T00:00:00Z");

    @Test
    void canonicalIdentityIgnoresGeneratedRowIdsButBindsResolvedMaterialAndPolicy() {
        UUID fileA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID fileB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var first = identities.identity(projectId, contractId, 0, "a".repeat(64), candidateId,
                null, List.of(material(fileA, 1, "b".repeat(64)), material(fileB, 2, "c".repeat(64))),
                "profile-v1", "rule-v1", "policy-v1");
        var reordered = identities.identity(projectId, contractId, 0, "a".repeat(64), candidateId,
                null, List.of(material(fileB, 2, "c".repeat(64)), material(fileA, 1, "b".repeat(64))),
                "profile-v1", "rule-v1", "policy-v1");
        assertThat(first).isEqualTo(reordered).matches("[0-9a-f]{64}");
        assertThat(identities.commandFingerprint("actor", projectId, contractId, 0,
                List.of(fileA, fileB), first)).isEqualTo(identities.commandFingerprint(
                        "actor", projectId, contractId, 0, List.of(fileB, fileA), reordered));
        assertThat(identities.identity(projectId, contractId, 1, "a".repeat(64), candidateId,
                null, List.of(material(fileA, 1, "b".repeat(64)), material(fileB, 2, "c".repeat(64))),
                "profile-v1", "rule-v1", "policy-v1")).isNotEqualTo(first);
        assertThat(identities.identity(projectId, contractId, 0, "a".repeat(64), candidateId,
                null, List.of(material(fileA, 1, "b".repeat(64)), material(fileB, 2, "c".repeat(64))),
                "profile-v1", "rule-v1", "policy-v2")).isNotEqualTo(first);
        assertThat(identities.identity(projectId, contractId, 0, "a".repeat(64), candidateId,
                null, List.of(material(fileA, 1, "d".repeat(64)), material(fileB, 2, "c".repeat(64))),
                "profile-v1", "rule-v1", "policy-v1")).isNotEqualTo(first);
    }

    @Test
    void explicitlyNullLegacyCandidateIdentityCanBeReadWithoutInventedProvenance() {
        UUID file = UUID.fromString("00000000-0000-0000-0000-000000000003");
        String snapshot = "a".repeat(64);
        String identity = identities.identity(projectId, contractId, 0, snapshot, null,
                null, List.of(material(file, 1, "b".repeat(64))), "profile-v1", "rule-v1", "policy-v1");
        assertThat(identity).isNotEqualTo(identities.identity(projectId, contractId, 0, snapshot,
                candidateId, null, List.of(material(file, 1, "b".repeat(64))),
                "profile-v1", "rule-v1", "policy-v1"));
        var run = new ContractReviewRun(UUID.randomUUID(), projectId, contractId, 0,
                null, null, 1, "{}", snapshot, "profile-v1", "rule-v1", "policy-v1",
                identity, "actor", ContractReviewRunStatus.PENDING, now,
                null, null, null, null, null, 0);
        assertThat(run.sourceCandidateId()).isNull();
        assertThat(run.sourceConfirmationId()).isNull();
    }

    @Test
    void historicalIdentityBindsPersistedRunSchemaVersionRatherThanCurrentCreationVersion() {
        UUID file = UUID.fromString("00000000-0000-0000-0000-000000000004");
        var selected = List.of(material(file, 1, "b".repeat(64)));
        String versionOne = identities.identity(1, projectId, contractId, 0, "a".repeat(64),
                candidateId, null, selected, "profile-v1", "rule-v1", "policy-v1");
        String versionTwo = identities.identity(2, projectId, contractId, 0, "a".repeat(64),
                candidateId, null, selected, "profile-v1", "rule-v1", "policy-v1");
        assertThat(versionTwo).isNotEqualTo(versionOne);
        assertThat(identities.identity(projectId, contractId, 0, "a".repeat(64), candidateId,
                null, selected, "profile-v1", "rule-v1", "policy-v1")).isEqualTo(versionOne);
    }

    private ContractReviewRunMaterial material(UUID file, int order, String sha) {
        return new ContractReviewRunMaterial(UUID.randomUUID(), UUID.randomUUID(), order, projectId, file,
                sha, stable(file + ":parse"), 1, "e".repeat(64), DocumentRole.CONTRACT_BODY,
                stable(file + ":role"), stable(file + ":identity"), null, stable("actor").toString(), now);
    }
    private static UUID stable(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
