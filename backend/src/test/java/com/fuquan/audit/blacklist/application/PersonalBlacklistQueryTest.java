package com.fuquan.audit.blacklist.application;

import com.fuquan.audit.blacklist.application.port.*;
import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import com.fuquan.audit.security.application.port.UploadOwnershipPort;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.common.exception.BusinessException;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalBlacklistQueryTest {
    @ParameterizedTest @ValueSource(strings={"PROCUREMENT_USER","BUDGET_USER","CONTRACT_USER"})
    void ownAlarmsOnlyInListsCountsExportsDetailsAndProcessing(String role) {
        var alerts=mock(BlacklistAlertRepository.class);
        var ownership=mock(UploadOwnershipPort.class);
        var user=new CurrentUser("alice","alice","Alice",Set.of(role));
        var query=new BlacklistQueryService(alerts,mock(BlacklistSupplierRepository.class),mock(BlacklistRuleRepository.class),
                mock(BlacklistSupplierOperationLogRepository.class),()->Optional.of(user),ownership);
        var own=BlacklistAlert.create(UUID.randomUUID().toString(),"甲公司","甲公司","EXACT",100);
        var foreign=BlacklistAlert.create(UUID.randomUUID().toString(),"乙公司","乙公司","EXACT",100);
        var unknown=BlacklistAlert.create("MANUAL","丙公司","丙公司","EXACT",100);
        when(ownership.ownsProcurementBatch(own.batchId(),"alice")).thenReturn(true);
        when(alerts.findAll(null)).thenReturn(List.of(foreign,own,unknown));
        when(alerts.findPage(null,null,null,null,0,Integer.MAX_VALUE)).thenReturn(List.of(foreign,own,unknown));
        when(alerts.findById(own.id())).thenReturn(Optional.of(own));
        when(alerts.findById(foreign.id())).thenReturn(Optional.of(foreign));
        assertThat(query.findAlerts(null)).containsExactly(own);
        assertThat(query.findAlertsForExport(null,null,null,null)).containsExactly(own);
        assertThat(query.findAlertPage(null,null,null,null,1,1).items()).containsExactly(own);
        assertThat(query.findAlertPage(null,null,null,null,1,1).total()).isEqualTo(1);
        assertThat(query.findAlertPage(null,null,null,null,2,1).items()).isEmpty();
        assertThat(query.requireAlert(own.id())).isEqualTo(own);
        assertThatThrownBy(()->query.requireAlert(foreign.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->new BlacklistAlertService(alerts,query).process(foreign.id(),"IGNORE","alice",""))
                .isInstanceOf(BusinessException.class);
        verify(alerts,never()).updateIfStatus(any(),any());
        when(ownership.ownsProcurementBatch(own.batchId(),"alice")).thenThrow(new IllegalStateException("db unavailable"));
        assertThatThrownBy(()->query.requireAlert(own.id())).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings={"PROCUREMENT_USER","BUDGET_USER","CONTRACT_USER"})
    void systemAdminOverridesModuleRoleAndCanReadAllAlerts(String role) {
        var alerts=mock(BlacklistAlertRepository.class);
        var ownership=mock(UploadOwnershipPort.class);
        var user=new CurrentUser("admin","admin","Admin",Set.of(role,"SYSTEM_ADMIN"));
        var query=new BlacklistQueryService(alerts,mock(BlacklistSupplierRepository.class),mock(BlacklistRuleRepository.class),
                mock(BlacklistSupplierOperationLogRepository.class),()->Optional.of(user),ownership);
        var one=BlacklistAlert.create(UUID.randomUUID().toString(),"甲公司","甲公司","EXACT",100);
        var two=BlacklistAlert.create(UUID.randomUUID().toString(),"乙公司","乙公司","EXACT",100);
        when(alerts.findAll(null)).thenReturn(List.of(one,two));
        when(alerts.findPage(null,null,null,null,0,Integer.MAX_VALUE)).thenReturn(List.of(one,two));
        when(alerts.findById(two.id())).thenReturn(Optional.of(two));
        when(alerts.count(null,null,null,null)).thenReturn(2L);

        assertThat(query.findAlerts(null)).containsExactly(one,two);
        assertThat(query.findAlertsForExport(null,null,null,null)).containsExactly(one,two);
        assertThat(query.findAlertPage(null,null,null,null,1,20).total()).isEqualTo(2);
        assertThat(query.requireAlert(two.id())).isEqualTo(two);
        verifyNoInteractions(ownership);
    }
}
