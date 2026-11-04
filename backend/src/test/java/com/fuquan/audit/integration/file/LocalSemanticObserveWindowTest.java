package com.fuquan.audit.integration.file;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LocalSemanticObserveWindowTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();
    final Instant start = Instant.parse("2026-10-20T00:00:00Z");

    @Test void immutableWindowRetainsExactHashAndRejectsExpiryWithoutExtendingTheSameWindow() throws Exception {
        Path file = directory.resolve("window.json");
        Files.writeString(file, proof());
        var reader = new LocalSemanticObserveWindow(file, json);
        var first = reader.read(start.plusSeconds(1));
        assertThat(first.expiresAt()).isEqualTo(start.plusSeconds(21600));
        assertThat(reader.read(start.plusSeconds(21599))).isEqualTo(first);
        assertThatThrownBy(() -> reader.read(start.plusSeconds(21600))).isInstanceOf(IllegalStateException.class);
        assertThat(reader.readIdentity(start.plusSeconds(21601))).isEqualTo(first);
        Files.writeString(file, proof().replace("candidate", "changed"));
        assertThatThrownBy(() -> reader.read(start.plusSeconds(2))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader.readIdentity(start.plusSeconds(21601))).isInstanceOf(IllegalStateException.class);
    }

    @Test void missingChangedOrFutureProofNeverGrantsAnObserveDispatch() throws Exception {
        Path file = directory.resolve("window.json");
        var reader = new LocalSemanticObserveWindow(file, json);
        assertThatThrownBy(() -> reader.read(start)).isInstanceOf(IllegalStateException.class);
        Files.writeString(file, proof());
        assertThatThrownBy(() -> reader.read(start.minusMillis(1))).isInstanceOf(IllegalStateException.class);
        reader.read(start);
        Files.writeString(file, proof()+" ");
        assertThatThrownBy(() -> reader.read(start)).isInstanceOf(IllegalStateException.class);
    }

    @Test void approvedPreloadWindowRetainsItsIdentityAcrossExpiryAndReopening() throws Exception {
        Path file = directory.resolve("window.json");
        String approvedProof = """
            {"expiresAtEpochMs":1792581012294,"initialCandidateSha":"eb4de27079f7c586a0e70c5d704778d9456226ba","kind":"LOCAL_V2_OBSERVE_WINDOW_V1","maximumActualCalls":120,"startSemantics":"CONSERVATIVE_PRE_FIRST_APPROVED_PRELOAD","startedAtEpochMs":1792559412294,"windowId":"051958bf-a54d-4684-89cb-2077a07a9320"}
            """;
        Files.writeString(file, approvedProof);
        var reader = new LocalSemanticObserveWindow(file, json);
        assertThatCode(() -> reader.read(Instant.ofEpochMilli(1792559412294L))).doesNotThrowAnyException();
        var first = reader.read(Instant.ofEpochMilli(1792559412294L));

        assertThat(first.windowId()).isEqualTo("051958bf-a54d-4684-89cb-2077a07a9320");
        assertThat(first.startedAtEpochMs()).isEqualTo(1792559412294L);
        assertThat(first.expiresAtEpochMs()).isEqualTo(1792581012294L);
        assertThat(reader.read(Instant.ofEpochMilli(1792581012293L))).isEqualTo(first);
        assertThatThrownBy(() -> reader.read(Instant.ofEpochMilli(1792581012294L)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(reader.readIdentity(Instant.ofEpochMilli(1792581012294L))).isEqualTo(first);
        assertThat(new LocalSemanticObserveWindow(file, json)
            .readIdentity(Instant.ofEpochMilli(1792581012295L))).isEqualTo(first);
        Files.writeString(file, approvedProof + " ");
        assertThatThrownBy(() -> reader.read(Instant.ofEpochMilli(1792559412295L)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader.readIdentity(Instant.ofEpochMilli(1792581012295L)))
            .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "CONSERVATIVE_PRE_RUN_POST, 120",
        "CONSERVATIVE_PRE_FIRST_APPROVED_PRELOAD, 200",
        "CONSERVATIVE_PRE_RUN_POST, 199",
        "CONSERVATIVE_PRE_RUN_POST, 201",
        "CONSERVATIVE_PRE_FIRST_APPROVED_PRELOAD, 119",
        "CONSERVATIVE_PRE_FIRST_APPROVED_PRELOAD, 121",
        "CONSERVATIVE_PRE_RUN_POST, 200.0",
        "CONSERVATIVE_PRE_FIRST_APPROVED_PRELOAD, 120.0",
        "CONSERVATIVE_PRE_RUN_POST, 4294967496",
        "CONSERVATIVE_PRE_FIRST_APPROVED_PRELOAD, 4294967416",
        "UNKNOWN, 120",
        "UNKNOWN, 200"
    })
    void rejectsMixedOrUnapprovedCallLimitSemantics(String semantics, String maximumActualCalls) throws Exception {
        Path file = directory.resolve("window.json");
        Files.writeString(file, proof(semantics, maximumActualCalls));
        var reader = new LocalSemanticObserveWindow(file, json);

        assertThatThrownBy(() -> reader.read(start)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader.readIdentity(start.plusSeconds(21601)))
            .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "CONSERVATIVE_PRE_RUN_POST, 200",
        "CONSERVATIVE_PRE_FIRST_APPROVED_PRELOAD, 120"
    })
    void approvedCombinationsStillRejectFutureInvalidDurationOrDifferentKind(String semantics,
            String maximumActualCalls) throws Exception {
        Path file = directory.resolve("window.json");
        String valid = proof(semantics, maximumActualCalls);
        Files.writeString(file, valid);
        var reader = new LocalSemanticObserveWindow(file, json);
        assertThatThrownBy(() -> reader.read(start.minusMillis(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader.readIdentity(start.minusMillis(1))).isInstanceOf(IllegalStateException.class);

        for (String invalid : new String[] {
            valid.replace("LOCAL_V2_OBSERVE_WINDOW_V1", "LOCAL_V2_OBSERVE_WINDOW_V2"),
            valid.replace(Long.toString(start.plusSeconds(21600).toEpochMilli()),
                Long.toString(start.plusSeconds(21600).toEpochMilli() + 1)),
            valid.replace(Long.toString(start.plusSeconds(21600).toEpochMilli()),
                Long.toString(start.plusSeconds(21600).toEpochMilli() - 1)),
            valid.replace(Long.toString(start.toEpochMilli()), "18446745866163951616"),
            valid.replace(Long.toString(start.plusSeconds(21600).toEpochMilli()), "18446745866185551616")
        }) {
            Files.writeString(file, invalid);
            var invalidReader = new LocalSemanticObserveWindow(file, json);
            assertThatThrownBy(() -> invalidReader.read(start)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> invalidReader.readIdentity(start.plusSeconds(21601)))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    private String proof() {
        return proof("CONSERVATIVE_PRE_RUN_POST", "200");
    }

    private String proof(String semantics, String maximumActualCalls) {
        return """
            {"kind":"LOCAL_V2_OBSERVE_WINDOW_V1","windowId":"11111111-1111-4111-8111-111111111111",
             "startedAtEpochMs":%d,"expiresAtEpochMs":%d,"maximumActualCalls":%s,
             "startSemantics":"%s","initialCandidateSha":"%s","note":"candidate"}
            """.formatted(start.toEpochMilli(),start.plusSeconds(21600).toEpochMilli(),
                maximumActualCalls,semantics,"a".repeat(40));
    }
}
