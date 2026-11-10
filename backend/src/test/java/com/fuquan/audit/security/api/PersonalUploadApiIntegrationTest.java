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
    private static UsernamePasswordAuthenticationToken actor(String id,String role){
        return new UsernamePasswordAuthenticationToken(new CurrentUser(id,id,id,Set.of(role)),null,Set.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }
    @ParameterizedTest @ValueSource(strings={"PROCUREMENT_USER","BUDGET_USER","CONTRACT_USER"})
    void everyModuleRoleHasOnlyItsWorkspaceAndOwnResources(String role)throws Exception {
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
            mvc.perform(get(path).with(authentication(user))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/projects/00000000-0000-0000-0000-000000000099/files").with(authentication(user)))
                .andExpect(status().is(role.equals("CONTRACT_USER")?404:403));
        if(!role.equals("BUDGET_USER"))mvc.perform(get("/api/v1/budget/reviews/00000000-0000-0000-0000-000000000099").with(authentication(user))).andExpect(status().isForbidden());
        if(!role.equals("CONTRACT_USER"))mvc.perform(get("/api/v1/contracts/00000000-0000-0000-0000-000000000099").with(authentication(user))).andExpect(status().isForbidden());
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
