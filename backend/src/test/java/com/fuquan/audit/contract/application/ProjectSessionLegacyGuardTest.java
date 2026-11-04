package com.fuquan.audit.contract.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.contract.domain.*;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProjectSessionLegacyGuardTest {
    @Test void projectAssociatedContractCannotCreateAnotherLegacyMaterialAuthority() {
        var sessions=mock(ContractReviewSessionRepository.class);
        var contracts=mock(ContractRepository.class);
        var associations=mock(com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort.class);
        UUID contractId=UUID.randomUUID();
        when(associations.findByContractId(contractId)).thenReturn(Optional.of(new com.fuquan.audit.contract.domain.project.ProjectContract(
                UUID.randomUUID(),contractId,UUID.randomUUID(),"actor",Instant.now())));
        var auth=mock(AuthContext.class);
        when(auth.currentUser()).thenReturn(Optional.of(new CurrentUser("actor","actor","Actor",Set.of("AUDITOR"))));
        var service=new ContractReviewSessionService(contracts,sessions,mock(ReviewDocumentRepository.class),mock(MaterialBindingRepository.class),
                mock(FileStoragePort.class),mock(ReviewFileValidator.class),mock(ContractReviewSessionTransactionCoordinator.class),auth,Clock.systemUTC(),
                new ReviewFilePolicy(100,Duration.ofMinutes(5)),mock(FlowDebugLogger.class));
        service.setProjectContracts(associations);
        var exception=assertThrows(BusinessException.class,() -> service.create(new CreateReviewSessionCommand(contractId,"Review",null,"create",new ReviewRequestMetadata("request","trace",null))));
        assertEquals(com.fuquan.audit.common.exception.BusinessErrorCode.CONFLICT,exception.errorCode());
        verifyNoInteractions(sessions,contracts);
    }
    @Test void rejectsReferenceBinaryEvenForExistingIdempotencyKeyBeforeAnyStorageOrParserCall() {
        var sessions = mock(ContractReviewSessionRepository.class);
        var storage = mock(FileStoragePort.class);
        var validator = mock(ReviewFileValidator.class);
        var coordinator = mock(ContractReviewSessionTransactionCoordinator.class);
        var auth = mock(AuthContext.class);
        var policy = mock(ReviewFilePolicy.class);
        when(policy.pendingRecoveryAge()).thenReturn(Duration.ofMinutes(5));
        when(auth.currentUser()).thenReturn(Optional.of(new CurrentUser("actor", "actor", "actor", Set.of("AUDITOR"))));
        var session = ContractReviewSession.createProjectBacked(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 0, "references", null, "actor", Instant.now());
        when(sessions.findById(session.id())).thenReturn(Optional.of(session));
        var service = new ContractReviewSessionService(mock(ContractRepository.class), sessions,
                mock(ReviewDocumentRepository.class), mock(MaterialBindingRepository.class), storage, validator,
                coordinator, auth, Clock.systemUTC(), policy, mock(FlowDebugLogger.class));
        assertThrows(BusinessException.class, () -> service.upload(new UploadReviewDocumentCommand(
                session.id(), ReviewDocumentKind.CONTRACT_BODY, 0, "contract.pdf", "application/pdf",
                new ByteArrayInputStream(new byte[]{1}), "existing-key", new ReviewRequestMetadata("request", "trace", null))));
        verifyNoInteractions(storage, validator);
        verify(coordinator, never()).startUpload(any(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any());
    }
}
