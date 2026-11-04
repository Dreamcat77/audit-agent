package com.fuquan.audit.contract.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.security.domain.CurrentUser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ContractCatalogApiIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void contractCatalogRequiresAuthenticationAndUsesStableDefaultPage() throws Exception {
        mockMvc.perform(get("/api/v1/contracts"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/contracts").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id")
                        .value("00000000-0000-0000-0000-000000000102"))
                .andExpect(jsonPath("$.data.items[0].lineItems").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].awardNotice").doesNotExist());
    }

    @Test
    void statusAndAllKeywordFieldsAreFilteredBeforePagination() throws Exception {
        assertSingle("LOCAL-PASS", "LOCAL-PASS-001");
        assertSingle("passing contract", "LOCAL-PASS-001");
        assertSingle("Synthetic Supplier A", "LOCAL-PASS-001");
        mockMvc.perform(get("/api/v1/contracts")
                        .param("keyword", "Synthetic Buyer")
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        mockMvc.perform(get("/api/v1/contracts")
                        .param("status", "MISSING")
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/contracts")
                        .param("keyword", "percent%_literal")
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void invalidPaginationAndLongKeywordAreRejected() throws Exception {
        for (String[] parameter : List.of(
                new String[] {"page", "0"},
                new String[] {"size", "0"},
                new String[] {"size", "101"},
                new String[] {"keyword", "x".repeat(129)})) {
            mockMvc.perform(get("/api/v1/contracts")
                            .param(parameter[0], parameter[1])
                            .with(authentication(auth())))
                    .andExpect(status().isBadRequest());
        }
    }

    private void assertSingle(String keyword, String contractNo) throws Exception {
        mockMvc.perform(get("/api/v1/contracts")
                        .param("status", "READY_FOR_REVIEW")
                        .param("keyword", keyword)
                        .param("page", "1")
                        .param("size", "1")
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].contractNo").value(contractNo));
    }

    private Authentication auth() {
        CurrentUser user = new CurrentUser("catalog-user", "catalog.user", "Catalog", Set.of("AUDITOR"));
        return UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_AUDITOR")));
    }
}
