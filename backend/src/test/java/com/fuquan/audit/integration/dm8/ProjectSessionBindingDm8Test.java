package com.fuquan.audit.integration.dm8;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.contract.domain.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import java.util.List;

class ProjectSessionBindingDm8Test {
    @Test void bindingRoundTripRetainsExactAuthorityAndHistoryVersion() {
        var mapper=mock(ProjectSessionBindingMapper.class);
        when(mapper.insert(any())).thenReturn(1);
        var repository=new Dm8ProjectSessionBindingRepository(mapper);
        var value=new ReviewSessionProjectFileBinding(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                "a".repeat(64),UUID.randomUUID(),2,"b".repeat(64),DocumentRole.CONTRACT_BODY,UUID.randomUUID(),UUID.randomUUID(),
                "CONTRACT_BODY:1",1,true,"actor",Instant.parse("2026-09-16T00:00:00Z"),3,0);
        repository.insert(value);
        var captured=ArgumentCaptor.forClass(ProjectSessionBindingPersistenceRow.class);
        verify(mapper).insert(captured.capture());
        when(mapper.findBySessionId(value.sessionId().toString())).thenReturn(List.of(captured.getValue()));
        assertEquals(List.of(value),repository.findBySessionId(value.sessionId()));
        repository.deactivateBySessionId(value.sessionId());
        verify(mapper).deactivateBySessionId(value.sessionId().toString());
    }
    @Test void sessionRoundTripCannotLoseReferenceAuthority() {
        var mapper=mock(ContractReviewSessionMapper.class);
        when(mapper.insert(any())).thenReturn(1);
        var repo=new Dm8ContractReviewSessionRepository(mapper);
        var session=ContractReviewSession.createProjectBacked(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),0,"Reference",null,"actor",Instant.now());
        repo.create(session);
        var capture=ArgumentCaptor.forClass(ContractReviewSessionPersistenceRow.class);
        verify(mapper).insert(capture.capture());
        when(mapper.findById(session.id().toString())).thenReturn(capture.getValue());
        assertEquals(session,repo.findById(session.id()).orElseThrow());
    }
}
