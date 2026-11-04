package com.fuquan.audit.contract.api.project;

import com.fuquan.audit.contract.application.debug.*;
import com.fuquan.audit.contract.application.reviewrun.*;
import com.fuquan.audit.contract.application.reviewrun.port.ContractReviewRunRepository;
import com.fuquan.audit.contract.domain.reviewrun.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.jayway.jsonpath.JsonPath;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("local")
class SanitizedDebugBundleApiIntegrationTest {
    @Autowired MockMvc mvc;
    @MockBean ContractReviewRunService reads;
    @Autowired ContractReviewRunRepository runs;
    String path(UUID project,UUID contract,UUID run) {
        return "/api/v1/projects/"+project+"/contracts/"+contract+"/review-runs/"+run+"/debug-bundles";
    }
    org.springframework.test.web.servlet.request.RequestPostProcessor user(String role) {
        var user=new CurrentUser(UUID.randomUUID().toString(),"synthetic","synthetic",Set.of(role));
        return authentication(new UsernamePasswordAuthenticationToken(user,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));
    }
    @Test void createReopenAndDownloadUseFocusedAuthenticatedApi() throws Exception {
        UUID p=UUID.randomUUID(),c=UUID.randomUUID(),r=UUID.randomUUID();String endpoint=path(p,c,r);
        var run=new ContractReviewRun(r,p,c,0,null,null,1,"CONFIDENTIAL_CANARY","a".repeat(64),"profile","rule","policy",
                "b".repeat(64),UUID.randomUUID().toString(),ContractReviewRunStatus.PENDING,Instant.now(),null,null,null,null,null,0);
        runs.insert(run);
        when(reads.get(p,c,r)).thenReturn(new ContractReviewRunCreateResult(run,List.of(),List.of(),List.of(),false));
        var actor=user("AUDITOR");
        String response=mvc.perform(post(endpoint).with(actor).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType("application/json").content("{}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        String id=JsonPath.read(response,"$.data.bundleId");
        assertThat(response).doesNotContain("manifestJson","storageKey","CONFIDENTIAL_CANARY");
        mvc.perform(get(endpoint+"/"+id).with(actor)).andExpect(status().isOk());
        String artifact=mvc.perform(get(endpoint+"/"+id+"/artifact").with(actor)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        assertThat(artifact).doesNotContain("CONFIDENTIAL_CANARY");
        mvc.perform(get(path(UUID.randomUUID(),c,r)+"/"+id).with(actor)).andExpect(status().isNotFound());
    }
    @Test void csrfRoleKeyAndStrictBodyFailClosed() throws Exception {
        String endpoint=path(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());var actor=user("AUDITOR");
        mvc.perform(post(endpoint).with(actor).header("Idempotency-Key","key").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(endpoint).with(user("LEGAL")).with(csrf()).header("Idempotency-Key","key").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(endpoint).with(actor).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(endpoint).with(actor).with(csrf()).header("Idempotency-Key","key").contentType("application/json").content("{\"prompt\":\"secret\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(endpoint).with(actor).with(csrf()).header("Idempotency-Key","key").contentType("application/json")
                .content("{\"contentLevel\":\"CUSTOMER_POLICY_APPROVED_REDACTED\"}"))
                .andExpect(status().isForbidden());
    }
}
