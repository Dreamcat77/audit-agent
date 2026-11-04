package com.fuquan.audit.security.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.security.domain.CurrentUser;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "audit.security.local-account.approval-users[0].id=00000000-0000-0000-0000-000000000021",
        "audit.security.local-account.approval-users[0].username=approval-target",
        "audit.security.local-account.approval-users[0].password-hash={noop}test",
        "audit.security.local-account.approval-users[0].real-name=Approval Target",
        "audit.security.local-account.approval-users[0].roles=PROCUREMENT_REVIEWER"
})
@TestPropertySource(properties = "audit.operation-audit.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class UserRoleAssignmentApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminCanAssignAndReadRole() throws Exception {
        String userId = "00000000-0000-0000-0000-000000000021";
        mockMvc.perform(post("/api/v1/system/users/{userId}/roles", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"FINANCE_REVIEWER\"}")
                        .with(authentication(authForRole("SYSTEM_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.roles").value(org.hamcrest.Matchers.hasItems(
                        "PROCUREMENT_REVIEWER", "FINANCE_REVIEWER")));

        mockMvc.perform(get("/api/v1/system/users/{userId}/roles", userId)
                        .with(authentication(authForRole("SYSTEM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles").value(org.hamcrest.Matchers.hasItems(
                        "PROCUREMENT_REVIEWER", "FINANCE_REVIEWER")));

        mockMvc.perform(get("/api/v1/audit/operations")
                        .param("userId", "test-operator")
                        .param("action", "ASSIGN_ROLE")
                        .param("resourceType", "USER")
                        .with(authentication(authForRole("SYSTEM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].resourceId").value(userId))
                .andExpect(jsonPath("$.data[0].resourceName").value("Approval Target"))
                .andExpect(jsonPath("$.data[0].beforeData").isNotEmpty())
                .andExpect(jsonPath("$.data[0].afterData").isNotEmpty())
                .andExpect(jsonPath("$.data[0].requestId").isNotEmpty())
                .andExpect(jsonPath("$.data[0].sourceIp").isNotEmpty());
    }

    @Test
    void nonAdminCannotAssignRole() throws Exception {
        mockMvc.perform(post("/api/v1/system/users/{userId}/roles", "00000000-0000-0000-0000-000000000021")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"FINANCE_REVIEWER\"}")
                        .with(authentication(authForRole("PROCUREMENT_REVIEWER")))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    private static Authentication authForRole(String role) {
        CurrentUser user = new CurrentUser("test-operator", "test-operator", "Test Operator", Set.of(role));
        return new UsernamePasswordAuthenticationToken(
                user,
                null,
                Set.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
