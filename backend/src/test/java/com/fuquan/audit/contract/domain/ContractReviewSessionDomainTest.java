package com.fuquan.audit.contract.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractReviewSessionDomainTest {
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void createsPreparingSessionWithContractVersionSnapshot() {
        ContractReviewSession session = session();

        assertThat(session.status()).isEqualTo(ReviewSessionStatus.PREPARING_MATERIALS);
        assertThat(session.contractVersion()).isEqualTo(7);
        assertThat(session.versionNo()).isZero();
        assertThat(session.materialsConfirmedAt()).isNull();
    }

    @Test
    void normalizesNameAndRemarkAndRejectsInvalidText() {
        ContractReviewSession session = ContractReviewSession.create(
                UUID.randomUUID(), UUID.randomUUID(), 1, "  审核一  ", "F02_DETERMINISTIC",
                "  备注  ", "user", NOW);

        assertThat(session.name()).isEqualTo("审核一");
        assertThat(session.remark()).isEqualTo("备注");
        assertThatThrownBy(() -> ContractReviewSession.normalizeName("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContractReviewSession.normalizeRemark("x".repeat(1001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyPreparingSessionCanAdvanceOrConfirm() {
        ContractReviewSession ready = session().confirmMaterials(NOW.plusSeconds(1));

        assertThat(ready.status()).isEqualTo(ReviewSessionStatus.READY);
        assertThat(ready.versionNo()).isEqualTo(1);
        assertThatThrownBy(() -> ready.advanceVersion(NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ready.confirmMaterials(NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void preparingAndReadySessionsCanBeSoftDeletedWithoutChangingBusinessStatus() {
        ContractReviewSession preparing = session();
        ContractReviewSession deletedPreparing = preparing.softDelete("actor", NOW.plusSeconds(1));
        ContractReviewSession ready = session().confirmMaterials(NOW.plusSeconds(1));
        ContractReviewSession deletedReady = ready.softDelete("actor", NOW.plusSeconds(2));

        assertThat(deletedPreparing.deleted()).isTrue();
        assertThat(deletedPreparing.status()).isEqualTo(ReviewSessionStatus.PREPARING_MATERIALS);
        assertThat(deletedPreparing.versionNo()).isEqualTo(1);
        assertThat(deletedReady.deleted()).isTrue();
        assertThat(deletedReady.status()).isEqualTo(ReviewSessionStatus.READY);
        assertThat(deletedReady.materialsConfirmedAt()).isEqualTo(ready.materialsConfirmedAt());
        assertThat(deletedReady.versionNo()).isEqualTo(ready.versionNo() + 1);
    }

    @Test
    void deleteMarkerIsPairedAndDeletedSessionRejectsAllDomainMutation() {
        ContractReviewSession original = session();
        ContractReviewSession deleted = original.softDelete("actor", NOW.plusSeconds(1));

        assertThatThrownBy(() -> new ContractReviewSession(
                original.id(), original.contractId(), original.contractVersion(), original.name(),
                original.reviewType(), original.status(), original.remark(), original.createdBy(),
                original.createdAt(), original.updatedAt(), original.materialsConfirmedAt(),
                original.versionNo(), NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deleted.softDelete("actor", NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> deleted.advanceVersion(NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> deleted.confirmMaterials(NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void documentTransitionsAreExplicitAndTerminal() {
        ReviewDocument pending = pendingDocument();
        ReviewDocument available = pending.markAvailable(NOW.plusSeconds(1));
        ReviewDocument failed = pending.markFailed(NOW.plusSeconds(1));

        assertThat(available.status()).isEqualTo(ReviewDocumentStatus.AVAILABLE);
        assertThat(available.storedAt()).isNotNull();
        assertThat(failed.status()).isEqualTo(ReviewDocumentStatus.UPLOAD_FAILED);
        assertThat(failed.failedAt()).isNotNull();
        assertThatThrownBy(() -> available.markFailed(NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bindingRequiresAvailableDocumentAndPreservesHistoryOnReplaceAndRemove() {
        ReviewDocument firstPending = pendingDocument();
        ReviewDocument first = firstPending.markAvailable(NOW.plusSeconds(1));
        MaterialBinding binding = MaterialBinding.activeFor(first, NOW.plusSeconds(1));
        ReviewDocument second = ReviewDocument.pending(
                UUID.randomUUID(), first.sessionId(), first.documentKind(), first.slotKey(),
                "next.pdf", "pdf", "application/pdf", 10, "b".repeat(64), "c".repeat(64),
                first.id(), 2, "user", NOW.plusSeconds(2)).markAvailable(NOW.plusSeconds(3));

        MaterialBinding replaced = binding.bind(second, NOW.plusSeconds(3));
        MaterialBinding removed = replaced.deactivate(NOW.plusSeconds(4));

        assertThat(replaced.boundDocumentId()).isEqualTo(second.id());
        assertThat(second.replacesDocumentId()).isEqualTo(first.id());
        assertThat(removed.active()).isFalse();
        assertThat(first.status()).isEqualTo(ReviewDocumentStatus.AVAILABLE);
        assertThatThrownBy(() -> MaterialBinding.activeFor(firstPending, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void slotKeysAreServerDeterminedByCardinality() {
        UUID id = UUID.randomUUID();
        assertThat(ReviewDocumentKind.CONTRACT_BODY.newSlotKey(id)).isEqualTo("CONTRACT_BODY");
        assertThat(ReviewDocumentKind.AWARD_NOTICE.newSlotKey(id)).isEqualTo("AWARD_NOTICE");
        assertThat(ReviewDocumentKind.TENDER_DOCUMENT.newSlotKey(id))
                .isEqualTo("TENDER_DOCUMENT:" + id);
        assertThat(ReviewDocumentKind.OTHER.newSlotKey(id)).isEqualTo("OTHER:" + id);
    }

    private ContractReviewSession session() {
        return ContractReviewSession.create(
                UUID.randomUUID(), UUID.randomUUID(), 7, "审核", "F02_DETERMINISTIC",
                null, "user", NOW);
    }

    private ReviewDocument pendingDocument() {
        return ReviewDocument.pending(
                UUID.randomUUID(), UUID.randomUUID(), ReviewDocumentKind.CONTRACT_BODY,
                "CONTRACT_BODY", "contract.pdf", "pdf", "application/pdf", 10,
                "a".repeat(64), "b".repeat(64), null, 0, "user", NOW);
    }
}
