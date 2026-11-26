package com.fuquan.audit.security.api;

import com.fuquan.audit.security.domain.CurrentUser;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"audit.contract.analysis.enabled=true","audit.contract.file-processing.worker-enabled=true","audit.procurement.auto-case.enabled=true"})
@AutoConfigureMockMvc @ActiveProfiles("local")
class PersonalUploadApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired com.fuquan.audit.security.infra.memory.InMemoryUserAccountRepository users;
    @Autowired com.fuquan.audit.budget.application.port.BudgetMaterialBatchRepository budgetBatches;
    @Test void existingOtherOwnersBatchIsHiddenFromOrdinaryUsersAndVisibleToAdministrator()throws Exception {
        var id=java.util.UUID.randomUUID();var key=java.util.UUID.randomUUID();
        budgetBatches.insert(new com.fuquan.audit.budget.domain.BudgetMaterialBatch(id,"scope-owner",null,null,
                java.util.List.of(new com.fuquan.audit.budget.domain.BudgetMaterialBatch.Member(key,"synthetic.pdf",10)),"OPEN",null,null,java.time.Instant.now()));
        for(String role:new String[]{"BUDGET_USER","PROCUREMENT_USER","CONTRACT_USER"}){
            mvc.perform(get("/api/v1/budget/material-uploads/batches/"+id).with(authentication(actor("scope-stranger",role))))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/budget/material-uploads/batches").with(authentication(actor("scope-stranger",role))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[*].batchId",org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(id.toString()))));
        }
        mvc.perform(get("/api/v1/budget/material-uploads/batches/"+id).with(authentication(actor("scope-owner","CONTRACT_USER"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/budget/material-uploads/batches/"+id).with(authentication(actor("scope-admin","SYSTEM_ADMIN"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/budget/material-uploads/batches").with(authentication(actor("scope-admin","SYSTEM_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[*].batchId",org.hamcrest.Matchers.hasItem(id.toString())));
    }
    @Test void administratorCreatesPersonWhoCanLoginAndAccessAllModulesWithoutManagingUsers()throws Exception {
        String username="created-"+java.util.UUID.randomUUID().toString().substring(0,8);
        String payload="{\"username\":\""+username+"\",\"displayName\":\"测试人员\",\"password\":\"OnlyTest123!\"}";
        var admin=actor("admin","SYSTEM_ADMIN");
        mvc.perform(post("/api/v1/system/users").with(authentication(admin)).contentType("application/json").content(payload))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/system/users").with(authentication(admin)).with(csrf()).contentType("application/json").content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.password").doesNotExist()).andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        mvc.perform(post("/api/v1/system/users").with(authentication(admin)).with(csrf()).contentType("application/json").content(payload))
                .andExpect(status().isConflict());
        var login=mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                .content("{\"username\":\""+username+"\",\"password\":\"OnlyTest123!\"}"))
                .andExpect(status().isOk()).andReturn();
        var session=(org.springframework.mock.web.MockHttpSession)login.getRequest().getSession(false);
        org.assertj.core.api.Assertions.assertThat(session).isNotNull();
        for(String path:new String[]{"/api/v1/budget/material-uploads/batches","/api/v1/procurement/cases/history","/api/v1/contract-analysis-cases","/api/v1/blacklist/entries"})
            mvc.perform(get(path).session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/system/users").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/system/users").session(session).with(csrf()).contentType("application/json").content(payload))
                .andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(users.findByUsername(username).orElseThrow().passwordHash()).startsWith("{bcrypt}");
    }
    private static UsernamePasswordAuthenticationToken actor(String id,String role){
        return new UsernamePasswordAuthenticationToken(new CurrentUser(id,id,id,Set.of(role)),null,Set.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }
    @ParameterizedTest @ValueSource(strings={"PROCUREMENT_USER","BUDGET_USER","CONTRACT_USER"})
    void everyOrdinaryRoleHasAllWorkspacesAndOnlyOwnResources(String role)throws Exception {
        var user=actor("personal-api-"+role,role);
        String[] ownPaths=switch(role) {
            case "BUDGET_USER" -> new String[]{"/api/v1/budget/material-uploads/batches"};
            case "PROCUREMENT_USER" -> new String[]{"/api/v1/procurement/cases/history","/api/v1/blacklist/alerts","/api/v1/blacklist/entries","/api/v1/blacklist/rules","/api/blacklist/alerts"};
            default -> new String[]{"/api/v1/contract-analysis-cases","/api/v1/contract-audit-tasks","/api/v1/contract-audit-tasks/summary"};
        };
        for(String path:ownPaths)
            mvc.perform(get(path).with(authentication(user))).andExpect(status().isOk());
        String[] otherPaths=switch(role) {
            case "BUDGET_USER" -> new String[]{"/api/v1/procurement/cases/history","/api/v1/contract-analysis-cases"};
            case "PROCUREMENT_USER" -> new String[]{"/api/v1/budget/material-uploads/batches","/api/v1/contract-analysis-cases"};
            default -> new String[]{"/api/v1/budget/material-uploads/batches","/api/v1/procurement/cases/history"};
        };
        for(String path:otherPaths)
            mvc.perform(get(path).with(authentication(user))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/projects/00000000-0000-0000-0000-000000000099/files").with(authentication(user)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/budget/reviews/00000000-0000-0000-0000-000000000099").with(authentication(user))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/contracts/00000000-0000-0000-0000-000000000099").with(authentication(user))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/system/upload-roles").with(authentication(user))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/notifications/unread-count").with(authentication(user))).andExpect(status().isOk());
    }
    @Test void systemAdminCanReadAllModuleCollectionsAcrossOwners()throws Exception {
        var admin=actor("system-admin","SYSTEM_ADMIN");
        for(String path:new String[]{"/api/v1/contract-analysis-cases","/api/v1/procurement/cases/history","/api/v1/budget/material-uploads/batches","/api/v1/contract-audit-tasks"})
            mvc.perform(get(path).with(authentication(admin))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/projects/00000000-0000-0000-0000-000000000099").with(authentication(admin)))
                .andExpect(status().isNotFound());
    }
    @Test void noPersonalRoleManagementEndpointIsPublished()throws Exception {
        mvc.perform(get("/api/v1/system/upload-roles").with(authentication(actor("admin","SYSTEM_ADMIN"))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/system/upload-roles").with(authentication(actor("admin","SYSTEM_ADMIN"))).with(csrf())
                .contentType("application/json").content("{\"username\":\"existing-user\",\"roleCode\":\"CONTRACT_USER\"}"))
                .andExpect(status().isNotFound());
    }
    @ParameterizedTest @ValueSource(strings={"PROCUREMENT_USER","BUDGET_USER","CONTRACT_USER"})
    void adminCanAssignEachPersistedRoleIdempotently(String role)throws Exception {
        var now=java.time.Instant.now();String id="role-target-"+role;
        users.seed(new com.fuquan.audit.security.domain.UserAccount(id,id,"{noop}test",id,
                com.fuquan.audit.security.domain.UserAccountStatus.ACTIVE,0,null,null,now,now,now,0,Set.of()));
        for(int i=0;i<2;i++)mvc.perform(post("/api/v1/system/users/"+id+"/roles").with(authentication(actor("admin","SYSTEM_ADMIN"))).with(csrf())
                .contentType("application/json").content("{\"roleCode\":\""+role+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.roles.length()").value(1)).andExpect(jsonPath("$.data.roles[0]").value(role));
    }
}
