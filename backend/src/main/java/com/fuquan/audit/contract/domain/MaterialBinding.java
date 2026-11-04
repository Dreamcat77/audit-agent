package com.fuquan.audit.contract.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 一个材料 Slot 当前指向的不可变 Document。 */
public record MaterialBinding(
        UUID id,
        UUID sessionId,
        String slotKey,
        ReviewDocumentKind documentKind,
        UUID boundDocumentId,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        long versionNo) {

    public MaterialBinding {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (slotKey == null || slotKey.isBlank() || slotKey.length() > 128) {
            throw new IllegalArgumentException("slotKey is invalid");
        }
        Objects.requireNonNull(documentKind, "documentKind must not be null");
        Objects.requireNonNull(boundDocumentId, "boundDocumentId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (versionNo < 0) {
            throw new IllegalArgumentException("versionNo must be non-negative");
        }
    }

    public static MaterialBinding activeFor(ReviewDocument document, Instant now) {
        requireAvailable(document);
        return new MaterialBinding(
                UUID.randomUUID(), document.sessionId(), document.slotKey(), document.documentKind(),
                document.id(), true, now, now, 0);
    }

    public MaterialBinding bind(ReviewDocument document, Instant now) {
        requireSameSlot(document);
        requireAvailable(document);
        return new MaterialBinding(
                id, sessionId, slotKey, documentKind, document.id(), true,
                createdAt, now, versionNo + 1);
    }

    public MaterialBinding deactivate(Instant now) {
        if (!active) {
            throw new IllegalStateException("binding is already inactive");
        }
        return new MaterialBinding(
                id, sessionId, slotKey, documentKind, boundDocumentId, false,
                createdAt, now, versionNo + 1);
    }

    private void requireSameSlot(ReviewDocument document) {
        if (!sessionId.equals(document.sessionId())
                || !slotKey.equals(document.slotKey())
                || documentKind != document.documentKind()) {
            throw new IllegalArgumentException("document does not belong to binding slot");
        }
    }

    private static void requireAvailable(ReviewDocument document) {
        if (document.status() != ReviewDocumentStatus.AVAILABLE) {
            throw new IllegalArgumentException("only available document can be bound");
        }
    }
}
