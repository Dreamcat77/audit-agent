package com.fuquan.audit.contract.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fuquan.audit.contract.domain.ContractReviewSession;
import com.fuquan.audit.contract.application.port.ContractReviewSessionRepository;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.*;
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
class ProjectReviewSessionApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ContractReviewSessionRepository sessions;
    String base(UUID p,UUID c) { return "/api/v1/projects/"+p+"/contracts/"+c+"/review-sessions"; }
    UsernamePasswordAuthenticationToken auth(String role) {
        return new UsernamePasswordAuthenticationToken(new CurrentUser("actor","actor","Actor",Set.of(role)),null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }
    @Test void readsScopedDurableReferencesAndRejectsWrongOwner() throws Exception {
        UUID p=UUID.randomUUID(),c=UUID.randomUUID();
        var session=ContractReviewSession.createProjectBacked(UUID.randomUUID(),p,c,0,"Reference",null,"actor",Instant.now()); sessions.create(session);
        mvc.perform(get(base(p,c)+"/"+session.id()).with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.session.id").value(session.id().toString()))
                .andExpect(jsonPath("$.data.bindings").isArray()).andExpect(header().exists("X-Request-Id"));
        mvc.perform(get(base(UUID.randomUUID(),c)+"/"+session.id()).with(authentication(auth("AUDITOR")))).andExpect(status().isNotFound());
        mvc.perform(get(base(p,c)+"/"+session.id()+"/materials").with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
    }
    @Test void guardsRolesCsrfAndNoPublicDelete() throws Exception {
        var base=base(UUID.randomUUID(),UUID.randomUUID());
        mvc.perform(get(base)).andExpect(status().isUnauthorized());
        mvc.perform(get(base).with(authentication(auth("VIEWER")))).andExpect(status().isForbidden());
        mvc.perform(post(base).with(authentication(auth("AUDITOR"))).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(base+"/"+UUID.randomUUID()).with(authentication(auth("AUDITOR"))).with(csrf()))
                .andExpect(status().isMethodNotAllowed());
    }
    @Test void scopedCreateValidatesBodyAndMissingAuthority() throws Exception {
        var base=base(UUID.randomUUID(),UUID.randomUUID());
        mvc.perform(post(base).with(authentication(auth("AUDITOR"))).with(csrf()).header("Idempotency-Key","empty")
                .contentType("application/json").content("{\"name\":\"review\",\"fileIds\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(base).with(authentication(auth("AUDITOR"))).with(csrf()).header("Idempotency-Key","unknown")
                .contentType("application/json").content("{\"name\":\"review\",\"fileIds\":[\""+UUID.randomUUID()+"\"]}"))
                .andExpect(status().isNotFound());
    }
}
