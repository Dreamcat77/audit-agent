package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.debug.*;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SanitizedDebugBundleRepositoryTest {
    @Test void metadataRoundTripAndCasRejectLostUpdate() {
        var mapper=mock(SanitizedDebugBundleMapper.class);
        var repository=new Dm8SanitizedDebugBundleRepository(mapper);
        UUID id=UUID.randomUUID();String json="{}";
        var row=new SanitizedDebugBundle(id,new DebugScope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,null),
                DebugContentLevel.METADATA_ONLY,SanitizedDebugBundle.Status.GENERATING,json,
                ContractSnapshotCanonicalizer.sha256("debug-bundle-v1:"+id),ContractSnapshotCanonicalizer.sha256(json),2,
                UUID.randomUUID().toString(),Instant.parse("2026-09-16T00:00:00Z"),"request","trace",0);
        when(mapper.insert(any())).thenAnswer(call -> { when(mapper.findById(id.toString())).thenReturn(call.getArgument(0)); return 1; });
        repository.insert(row);
        assertThat(repository.findById(id)).contains(row);
        var ready=row.withStatus(SanitizedDebugBundle.Status.AVAILABLE);
        when(mapper.transition(id.toString(),"GENERATING",0L,"AVAILABLE",1L)).thenReturn(1,0);
        assertThat(repository.transition(row,ready)).isEqualTo(ready);
        assertThatThrownBy(() -> repository.transition(row,ready)).isInstanceOf(com.fuquan.audit.common.exception.BusinessException.class);
    }
    @Test void controlledMigrationOwnsOnlyBundleMetadata() throws Exception {
        String sql=Files.readString(Path.of("../db/dm8/CONTRACT__sanitized_debug_bundle.sql"));
        assertThat(sql).contains("-- MIGRATION-NAMESPACE: CONTRACT","-- DEPENDS-ON: review-run-results","-- EXECUTION: CONTROLLED_MANUAL",
                "-- IDEMPOTENCY: NOT_ASSUMED","CREATE TABLE SANITIZED_DEBUG_BUNDLE","REFERENCES CONTRACT_REVIEW_RUN","REFERENCES SYS_USER");
        assertThat(sql).doesNotContain("ALTER TABLE","DROP TABLE","PARSED_TEXT","RAW_RESPONSE","V023");
    }
}
