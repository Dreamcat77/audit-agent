package com.fuquan.audit.security.infra;

import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.application.port.UploadOwnershipPort;
import com.fuquan.audit.security.domain.CurrentUser;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.HandlerMapping;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalUploadAccessInterceptorTest {
    private final UploadOwnershipPort ownership=mock(UploadOwnershipPort.class);
    private final UUID project=UUID.randomUUID(),contract=UUID.randomUUID(),task=UUID.randomUUID();
    private PersonalUploadAccessInterceptor access(String...roles){
        AuthContext auth=()->Optional.of(new CurrentUser("alice","alice","Alice",Set.of(roles)));
        return new PersonalUploadAccessInterceptor(auth,ownership);
    }
    private MockHttpServletRequest request(String route,Map<String,String> vars){
        var r=new MockHttpServletRequest("GET",route);
        r.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,route);
        r.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,vars);return r;
    }
    @ParameterizedTest @ValueSource(strings={"PROCUREMENT_USER","BUDGET_USER","CONTRACT_USER"})
    void eachPersonalRoleCanOnlyUseItsOwnModuleAndSharedAccountEndpoints(String role)throws Exception {
        var allowed = switch (role) {
            case "BUDGET_USER" -> List.of("/api/v1/budget/material-uploads/batches", "/api/v1/material-transfers/{kind}/{operation}", "/api/v1/blacklist/alerts");
            case "PROCUREMENT_USER" -> List.of("/api/v1/procurement/cases/history", "/api/v1/blacklist/alerts", "/api/blacklist/alerts");
            default -> List.of("/api/v1/contract-analysis-cases", "/api/v1/contract-audit-tasks", "/api/v1/contract-audit-tasks/summary", "/api/v1/blacklist/alerts");
        };
        for(String route:allowed) {
            var vars=route.contains("{kind}")?Map.of("kind","budget"):Map.<String,String>of();
            assertThat(access(role).preHandle(request(route,vars),new MockHttpServletResponse(),this)).isTrue();
        }
        for(String route:List.of("/api/v1/auth/me","/api/v1/notifications/unread-count","/api/v1/health"))
            assertThat(access(role).preHandle(request(route,Map.of()),new MockHttpServletResponse(),this)).isTrue();
        for(String route:List.of("/api/v1/procurement/cases/history","/api/v1/budget/material-uploads/batches","/api/v1/contract-analysis-cases")) {
            boolean own = switch(role) {
                case "BUDGET_USER" -> route.contains("/budget/");
                case "PROCUREMENT_USER" -> route.contains("/procurement/");
                default -> route.contains("/contract-analysis-cases");
            };
            if(!own) assertThatThrownBy(()->access(role).preHandle(request(route,Map.of()),new MockHttpServletResponse(),this))
                    .isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(ownership);
    }
    @ParameterizedTest @ValueSource(strings={"PROCUREMENT_USER","BUDGET_USER","CONTRACT_USER"})
    void allOrganizationBlacklistOperationsAreSharedAcrossModuleRoles(String role)throws Exception {
        for(String route:List.of(
                "/api/v1/blacklist/check",
                "/api/v1/blacklist/check-batch",
                "/api/v1/blacklist/check-batch/headers/preview",
                "/api/v1/blacklist/check-batch-upload",
                "/api/v1/blacklist/alerts",
                "/api/v1/blacklist/alerts/{id}",
                "/api/v1/blacklist/alerts/{id}/handle",
                "/api/v1/blacklist/entries",
                "/api/v1/blacklist/entries/query",
                "/api/v1/blacklist/entries/{id}",
                "/api/v1/blacklist/entries/import",
                "/api/v1/blacklist/entries/export",
                "/api/v1/blacklist/rules",
                "/api/v1/blacklist/rules/{id}",
                "/api/blacklist/check",
                "/api/blacklist/entries",
                "/api/blacklist/rules")) {
            assertThat(access(role).preHandle(request(route,Map.of()),new MockHttpServletResponse(),this))
                    .as("%s can use %s",role,route).isTrue();
        }
        verifyNoInteractions(ownership);
    }
    @ParameterizedTest @ValueSource(strings={"PROCUREMENT_USER","BUDGET_USER","CONTRACT_USER"})
    void anotherPersonsProjectCannotExposeOriginalsReportsOrReviewCommands(String role) {
        var vars=Map.of("projectId",project.toString());
        for(String suffix:List.of("/files/{fileId}/content","/files/{fileId}/parsed-text","/contracts/{contractId}/review-runs","/metadata"))
            assertThatThrownBy(()->access(role).preHandle(request("/api/v1/projects/{projectId}"+suffix,vars),new MockHttpServletResponse(),this))
                    .isInstanceOf(BusinessException.class);
    }
    @Test void ownProjectCannotBeCombinedWithForeignContract() {
        when(ownership.ownsProject(project,"alice")).thenReturn(true);
        assertThatThrownBy(()->access("CONTRACT_USER").preHandle(request("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs",
                Map.of("projectId",project.toString(),"contractId",contract.toString())),new MockHttpServletResponse(),this)).isInstanceOf(BusinessException.class);
        verify(ownership).ownsContract(contract,project,"alice");
    }
    @Test void ownedProjectAndContractRemainUsable()throws Exception {
        when(ownership.ownsProject(project,"alice")).thenReturn(true);
        when(ownership.ownsContract(contract,project,"alice")).thenReturn(true);
        assertThat(access("CONTRACT_USER").preHandle(request("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs",
                Map.of("projectId",project.toString(),"contractId",contract.toString())),new MockHttpServletResponse(),this)).isTrue();
    }
    @Test void oldBudgetTaskUrlsRequirePersistedUploadOwnership()throws Exception {
        var r=request("/api/v1/budget/reviews/{taskId}/items/{itemId}",Map.of("taskId",task.toString()));
        assertThatThrownBy(()->access("BUDGET_USER").preHandle(r,new MockHttpServletResponse(),this)).isInstanceOf(BusinessException.class);
        when(ownership.ownsBudgetTask(task,"alice")).thenReturn(true);
        assertThat(access("BUDGET_USER").preHandle(r,new MockHttpServletResponse(),this)).isTrue();
    }
    @Test void systemAdminOverridesPersonalRolesAndCanReadOtherOwnersData()throws Exception {
        assertThat(access("CONTRACT_USER","AUDITOR","SYSTEM_ADMIN").preHandle(
                request("/api/v1/projects/{projectId}",Map.of("projectId",project.toString())),new MockHttpServletResponse(),this)).isTrue();
        verifyNoInteractions(ownership);
    }
    @Test void globalCollectionsAndLegacyTransferCannotLeakOtherUploads() {
        for(String route:List.of("/api/v1/projects","/api/v1/contracts","/api/v1/budget/validation-records","/api/v1/procurement/projects"))
            assertThatThrownBy(()->access("BUDGET_USER").preHandle(request(route,Map.of()),new MockHttpServletResponse(),this)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->access("BUDGET_USER").preHandle(request("/api/v1/material-transfers/{kind}/{operation}",Map.of("kind","project")),new MockHttpServletResponse(),this)).isInstanceOf(BusinessException.class);
    }
    @Test void repositoryFailureMustNotGrantAccess() {
        when(ownership.ownsProject(project,"alice")).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(()->access("CONTRACT_USER").preHandle(request("/api/v1/projects/{projectId}",Map.of("projectId",project.toString())),new MockHttpServletResponse(),this)).isInstanceOf(IllegalStateException.class);
    }
    @Test void existingNonPersonalAdminContractRemainsUnchanged()throws Exception {
        assertThat(access("SYSTEM_ADMIN").preHandle(request("/api/v1/system/users/id/roles",Map.of()),new MockHttpServletResponse(),this)).isTrue();
        verifyNoInteractions(ownership);
    }

    @Test void assignedMultiplePersonalModulesCanAccessEachAssignedModule()throws Exception {
        for(String route:List.of("/api/v1/budget/material-uploads/batches","/api/v1/procurement/cases/history","/api/v1/contract-analysis-cases"))
            assertThat(access("BUDGET_USER","PROCUREMENT_USER","CONTRACT_USER").preHandle(
                    request(route,Map.of()),new MockHttpServletResponse(),this)).isTrue();
    }
}
