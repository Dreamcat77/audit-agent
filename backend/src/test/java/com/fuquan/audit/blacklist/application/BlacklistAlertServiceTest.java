package com.fuquan.audit.blacklist.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fuquan.audit.blacklist.application.port.BlacklistAlertRepository;
import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import com.fuquan.audit.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class BlacklistAlertServiceTest {

    @Test
    void rejectsUnknownActionWithoutChangingTheAlert() {
        BlacklistAlertRepository repository = mock(BlacklistAlertRepository.class);
        BlacklistQueryService queryService = mock(BlacklistQueryService.class);
        BlacklistAlert alert = pendingAlert();
        when(queryService.requireAlert(alert.id())).thenReturn(alert);
        BlacklistAlertService service = new BlacklistAlertService(repository, queryService);

        assertThatThrownBy(() -> service.process(
                alert.id(), "PROCESSED_TYPO", "auditor", "reviewed"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的告警处理动作");

        verifyNoInteractions(repository);
    }

    @Test
    void concurrentDifferentDecisionCannotOverwriteTheFirstDecision() {
        BlacklistAlertRepository repository = mock(BlacklistAlertRepository.class);
        BlacklistQueryService queryService = mock(BlacklistQueryService.class);
        BlacklistAlert pending = pendingAlert();
        BlacklistAlert alreadyIgnored = pending.ignore("first-auditor", "ignored first");
        when(queryService.requireAlert(pending.id())).thenReturn(pending, alreadyIgnored);
        when(repository.updateIfStatus(any(), anyString())).thenReturn(false);
        BlacklistAlertService service = new BlacklistAlertService(repository, queryService);

        assertThatThrownBy(() -> service.process(
                pending.id(), "PROCESSED", "second-auditor", "process second"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("其他用户处理");
    }

    private static BlacklistAlert pendingAlert() {
        return BlacklistAlert.create(
                "batch-1", "供应商甲", "供应商甲", "EXACT", 100);
    }
}
