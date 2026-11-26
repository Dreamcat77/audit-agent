package com.fuquan.audit.security.application;

import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.security.application.port.*;
import com.fuquan.audit.security.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserAdministrationServiceTest {
    final UserAdministrationPort store=mock(UserAdministrationPort.class);
    final UserAccountRepository lookup=mock(UserAccountRepository.class);
    final OperationAuditService audit=mock(OperationAuditService.class);
    final org.springframework.security.crypto.password.PasswordEncoder encoder=PasswordEncoderFactories.createDelegatingPasswordEncoder();
    final UserAdministrationService service=new UserAdministrationService(store,lookup,encoder,audit);
    final CurrentUser admin=new CurrentUser("admin-id","admin","Administrator",Set.of("SYSTEM_ADMIN"));
    @Test void createsOnlyOrdinaryUserAndNeverReturnsOrAuditsPassword() {
        var result=service.create(" New.Person ","测试人员","test-only-password",admin);
        var capture=org.mockito.ArgumentCaptor.forClass(UserAccount.class);verify(store).create(capture.capture());
        var stored=capture.getValue();
        assertThat(stored.username()).isEqualTo("new.person");
        assertThat(encoder.matches("test-only-password",stored.passwordHash())).isTrue();
        assertThat(stored.roles()).containsExactly("CONTRACT_USER");
        assertThat(result.toString()).doesNotContain("password",stored.passwordHash());
        verify(audit).success(eq("CREATE_USER"),eq(admin),eq("USER"),eq(stored.id()),eq("测试人员"),isNull(),eq(result),anyString());
    }
    @Test void nonAdministratorCannotListOrCreate() {
        var actor=new CurrentUser("member","member","Member",Set.of("CONTRACT_USER"));
        assertThatThrownBy(()->service.create("person","人员","test-only-password",actor)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.list(1,20,actor)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(store,lookup,audit);
    }
    @Test void databaseUniqueRaceIsReportedAsConflict() {
        doThrow(new org.springframework.dao.DuplicateKeyException("unique")).when(store).create(any());
        assertThatThrownBy(()->service.create("person","人员","test-only-password",admin)).isInstanceOf(BusinessException.class).hasMessage("用户名已存在");
        verifyNoInteractions(audit);
    }
    @Test void rejectsMalformedNamesAndOversizedBcryptInput() {
        for(String name:Arrays.asList(null,"","../x","a"))assertThatThrownBy(()->service.create(name,"人员","test-only-password",admin)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.create("person","人员","密".repeat(25),admin)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(store);
    }
}
