package com.fuquan.audit.integration.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticObserveWindowPort;
import com.fuquan.audit.contract.application.reviewrun.semantic.SemanticCheckpointTypes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Read-only proof reader. An absent, altered or expired window never authorizes a dispatch. */
public final class LocalSemanticObserveWindow implements SemanticObserveWindowPort {
    private final Path path;
    private final ObjectMapper json;
    private String observedHash;

    public LocalSemanticObserveWindow(Path path, ObjectMapper json) {
        this.path = path.toAbsolutePath().normalize(); this.json = json;
    }

    @Override public synchronized Window read(Instant now) {
        return read(now,true);
    }

    @Override public synchronized Window readIdentity(Instant now) {
        return read(now,false);
    }

    private Window read(Instant now, boolean requireActive) {
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(16_385);
            if (bytes.length > 16_384) throw new IllegalArgumentException("observe window exceeds bound");
            String content = new String(bytes, StandardCharsets.UTF_8);
            String hash = SemanticCheckpointTypes.digest(content);
            if (observedHash != null && !observedHash.equals(hash)) throw new IllegalArgumentException("observe window changed");
            var value = json.readTree(content);
            if (!"LOCAL_V2_OBSERVE_WINDOW_V1".equals(value.path("kind").asText())
                    || !value.path("startedAtEpochMs").isIntegralNumber()
                    || !value.path("startedAtEpochMs").canConvertToLong()
                    || !value.path("expiresAtEpochMs").isIntegralNumber()
                    || !value.path("expiresAtEpochMs").canConvertToLong()
                    || !value.path("maximumActualCalls").isIntegralNumber()
                    || !value.path("maximumActualCalls").canConvertToInt()
                    || !("CONSERVATIVE_PRE_RUN_POST".equals(value.path("startSemantics").asText())
                            && value.path("maximumActualCalls").asInt() == 200
                        || "CONSERVATIVE_PRE_FIRST_APPROVED_PRELOAD".equals(value.path("startSemantics").asText())
                            && value.path("maximumActualCalls").asInt() == 120)
                    || !value.path("initialCandidateSha").asText().matches("[0-9a-f]{40}"))
                throw new IllegalArgumentException("invalid observe window proof");
            var window = new Window(value.path("windowId").asText(), value.path("startedAtEpochMs").asLong(),
                    value.path("expiresAtEpochMs").asLong(), hash);
            if (now.toEpochMilli() < window.startedAtEpochMs() || (requireActive && !now.isBefore(window.expiresAt())))
                throw new IllegalArgumentException("observe window is not active");
            observedHash = hash;
            return window;
        } catch (java.io.IOException | RuntimeException invalid) {
            throw new IllegalStateException("semantic observe window is unavailable or invalid");
        }
    }
}
