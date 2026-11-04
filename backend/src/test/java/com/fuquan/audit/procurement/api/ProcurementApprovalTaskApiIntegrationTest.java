package com.fuquan.audit.procurement.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.security.domain.CurrentUser;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ProcurementApprovalTaskApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void currentApproverCanQueryPendingTasks() throws Exception {
        CurrentUser user = new CurrentUser(
                "reviewer-user", "reviewer-user", "采购审查员", Set.of("PROCUREMENT_REVIEWER"));
        var authentication = new UsernamePasswordAuthenticationToken(
                user, null, Set.of(new SimpleGrantedAuthority("ROLE_PROCUREMENT_REVIEWER")));

        mockMvc.perform(get("/api/v1/procurement/approval/tasks")
                        .with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }
}
