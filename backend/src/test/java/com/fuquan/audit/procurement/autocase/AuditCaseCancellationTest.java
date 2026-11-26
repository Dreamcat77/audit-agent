package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditCaseCancellationTest {
    @Test void cancelledFilesUseExistingDm8StatusAndRetainOnlyCleanupIdentity() {
        var now=Instant.now();
        var document=new AuditCase.Document(UUID.randomUUID(),"合成材料.pdf",10,"a".repeat(64),"b".repeat(64),
                "PARSING",List.of(new com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Segment("page:1","合成正文")),List.of(),"PDF_OCR",null,2);
        var active=new AuditCase(UUID.randomUUID(),"owner",UUID.randomUUID(),"c".repeat(64),"RUNNING",1,
                "worker-lease",now.plusSeconds(60),1,now,List.of(document),null);
        var cancelled=active.cancel();
        assertThat(cancelled.status()).isEqualTo("FAILED");
        assertThat(cancelled.cancelled()).isTrue();
        assertThat(cancelled.documents()).singleElement().satisfies(file->{
            assertThat(file.status()).isEqualTo("FAILED");
            assertThat(file.errorCode()).isEqualTo("USER_CANCELLED");
            assertThat(file.storageKey()).isEqualTo(document.storageKey());
            assertThat(file.segments()).isEmpty();assertThat(file.facts()).isEmpty();
            assertThat(file.readCheckpoint()).isNull();assertThat(file.parseMethod()).isNull();
        });
    }
    @Test void cancellationIsDurableAndRejectsAnAlreadyRunningWorkersStaleCheckpoint() {
        var repository=new InMemoryAuditCaseRepository();
        var now=Instant.now();
        var queued=new AuditCase(UUID.randomUUID(),"owner",UUID.randomUUID(),"a".repeat(64),"QUEUED",1,
                "upload-lease",now.plusSeconds(60),0,now,List.of(),null);
        repository.insert(queued);
        var running=queued.claim("worker-lease",now.plusSeconds(60));
        assertThat(repository.replace(queued,running)).isTrue();

        var cancelled=running.cancel();
        assertThat(repository.replace(running,cancelled)).isTrue();
        assertThat(repository.find(queued.id()).orElseThrow().cancelled()).isTrue();
        var staleCompletion=running.update("COMPLETED",List.of(),null);
        assertThat(repository.replace(running,staleCompletion)).isFalse();
        assertThat(repository.replace(cancelled,cancelled.update("COMPLETED",List.of(),null))).isFalse();
        assertThat(repository.replace(cancelled,cancelled.update("FAILED",List.of(),null))).isTrue();
        assertThat(repository.pending(now.plusSeconds(120)).stream().map(AuditCase::id).toList()).doesNotContain(queued.id());
    }
}
