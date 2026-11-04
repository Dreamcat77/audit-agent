package com.fuquan.audit.contract.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewSessionSourceKindTest {
    private final Instant now = Instant.parse("2026-09-16T00:00:00Z");

    @Test void legacyFactoryKeepsHistoricalAuthority() {
        var session = ContractReviewSession.create(UUID.randomUUID(), UUID.randomUUID(), 0,
                "legacy", "F02_DETERMINISTIC", null, "actor", now);
        assertEquals(ReviewSessionSourceKind.LEGACY_REVIEW_DOCUMENT, session.sourceKind());
        assertNull(session.projectId());
        assertEquals(session.sourceKind(), session.confirmMaterials(now).sourceKind());
    }

    @Test void projectSourceAndOwnershipRemainFrozenAcrossEveryTransition() {
        UUID project = UUID.randomUUID();
        var session = ContractReviewSession.createProjectBacked(UUID.randomUUID(), project,
                UUID.randomUUID(), 0, "references", null, "actor", now);
        var advanced = session.advanceVersion(now);
        var ready = advanced.confirmMaterials(now);
        var tombstone = ready.softDelete("actor", now);
        for (var value : new ContractReviewSession[]{session, advanced, ready, tombstone}) {
            assertEquals(ReviewSessionSourceKind.PROJECT_FILE_REFERENCE, value.sourceKind());
            assertEquals(project, value.projectId());
        }
        assertThrows(IllegalStateException.class, () -> ready.advanceVersion(now));
    }

    @Test void projectFactoryCannotCreateUnownedReferenceSession() {
        assertThrows(IllegalArgumentException.class, () -> ContractReviewSession.createProjectBacked(
                UUID.randomUUID(), null, UUID.randomUUID(), 0, "references", null, "actor", now));
    }
}
