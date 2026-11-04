package com.fuquan.audit.contract.application.reviewrun.semantic;

import java.time.Instant;

/** Immutable local acceptance-window proof, separate from durable per-plan budgets. */
public interface SemanticObserveWindowPort {
    Window read(Instant now);
    /** Identity only; callers must independently prove a completed plan before using this for replay. */
    default Window readIdentity(Instant now) { return read(now); }
    record Window(String windowId, long startedAtEpochMs, long expiresAtEpochMs, String sha256) {
        public Window {
            java.util.UUID.fromString(windowId);
            SemanticCheckpointTypes.hash(sha256);
            if (startedAtEpochMs < 0 || expiresAtEpochMs - startedAtEpochMs != 21_600_000L)
                throw new IllegalArgumentException("invalid semantic observe window");
        }
        public Instant expiresAt() { return Instant.ofEpochMilli(expiresAtEpochMs); }
    }
}
