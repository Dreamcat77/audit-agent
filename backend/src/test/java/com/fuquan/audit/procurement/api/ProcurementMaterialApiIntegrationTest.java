package com.fuquan.audit.procurement.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ProcurementMaterialApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired com.fuquan.audit.common.material.application.MaterialIntakeService service;
    @MockitoBean com.fuquan.audit.common.material.application.MaterialIntakeWorker worker;
    @MockitoBean MaterialFieldAiPort ai;
    private static UsernamePasswordAuthenticationToken auth(String id, String role) {
        return new UsernamePasswordAuthenticationToken(new CurrentUser(id, id, "用户", Set.of(role)), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    private static MockMultipartFile file() throws Exception {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("项目名称：办公设备更新；预算金额：12万元");
            document.write(output);
            return new MockMultipartFile("file", "采购需求.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", output.toByteArray());
        }
    }
    @Test void uploadReopenOperationAndCreateFromParsedProcurementFields() throws Exception {
        when(ai.extract(anyString(), anyString(), anyString(), anyList())).thenAnswer(i -> {
            List<MaterialFieldAiPort.Segment> segments = i.getArgument(3);
            var s = segments.get(0);
            return new MaterialFieldAiPort.Result(List.of(new MaterialFieldAiPort.Row("one", List.of(
                    new MaterialFieldAiPort.Fact("projectName", "办公设备更新", s.locator(), s.text()),
                    new MaterialFieldAiPort.Fact("budgetAmount", "12万元", s.locator(), s.text())))), "test-model", "v1");
        });
        String key = UUID.randomUUID().toString(); var uploader = auth("intake-owner", "PROCUREMENT_APPLICANT");
        var response = mvc.perform(multipart("/api/v1/procurement/materials").file(file()).header("Idempotency-Key", key)
                .with(authentication(uploader)).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.fields").isEmpty())
                .andExpect(jsonPath("$.data.storageKey").doesNotExist()).andReturn();
        String id = json.readTree(response.getResponse().getContentAsString()).path("data").path("id").asText();
        verifyNoInteractions(ai);
        service.processPending(UUID.fromString(id),(CurrentUser)uploader.getPrincipal(),r -> {});
        mvc.perform(get("/api/v1/procurement/materials/{id}", id).with(authentication(uploader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fields.projectName").value("办公设备更新"));
        mvc.perform(get("/api/v1/procurement/materials/operations/{key}", key).with(authentication(uploader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
        mvc.perform(get("/api/v1/procurement/materials/operations/{key}", key).with(authentication(auth("other", "AUDITOR"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(post("/api/v1/procurement/projects").contentType("application/json").content(json.writeValueAsString(Map.of(
                "sourceProjectId", id, "projectName", "办公设备更新（已核对）", "departmentName", "信息中心", "procurementType", "货物",
                "budgetAmount", "120000.00", "subjectCode", "A1", "subjectName", "设备", "procurementMethod", "公开招标", "applicantName", "经办人")))
                .with(authentication(uploader)).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNotEmpty());
    }
    @Test void uploadRequiresRoleCsrfAndOperationKey() throws Exception {
        mvc.perform(multipart("/api/v1/procurement/materials").file(file()).header("Idempotency-Key", UUID.randomUUID())
                .with(authentication(auth("owner", "PROCUREMENT_APPLICANT")))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/procurement/materials").file(file()).header("Idempotency-Key", UUID.randomUUID())
                .with(authentication(auth("owner", "CONTRACT_MANAGER"))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/procurement/materials").file(file())
                .with(authentication(auth("owner", "PROCUREMENT_APPLICANT"))).with(csrf())).andExpect(status().isBadRequest());
        verifyNoInteractions(ai);
    }
    @Test void privateMaterialsCannotBeReadRetriedListedOrLinkedByAnotherAccountEvenAnAdmin() throws Exception {
        var owner = auth("privacy-owner-" + UUID.randomUUID(), "PROCUREMENT_APPLICANT");
        String key = UUID.randomUUID().toString();
        var response = mvc.perform(multipart("/api/v1/procurement/materials").file(file())
                .header("Idempotency-Key", key).with(authentication(owner)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        String id = json.readTree(response.getResponse().getContentAsString()).path("data").path("id").asText();
        var now = java.time.Instant.now();
        var fields = new com.fuquan.audit.common.material.domain.MaterialIntakeFields("私有测试需求", null,
                null, "货物", null, null, null, null, null, null, Map.of(), List.of(), "test", "v1");
        var repository = materialRepository;
        var record = repository.find(UUID.fromString(id)).orElseThrow();
        record = record.claim(UUID.randomUUID().toString(), now.plusSeconds(120), now);
        org.assertj.core.api.Assertions.assertThat(repository.claimPending(record, now)).isTrue();
        org.assertj.core.api.Assertions.assertThat(repository.complete(record.finish("PARSED", fields, "TEST", null, now))).isTrue();
        mvc.perform(get("/api/v1/procurement/materials/{id}", id).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fields.projectName").value("私有测试需求"));
        mvc.perform(get("/api/v1/procurement/projects/source-projects").with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(id));
        // Primary and supplementary IDs must both be checked before any project write.
        for (String role : List.of("PROCUREMENT_APPLICANT", "PROCUREMENT_MANAGER", "AUDITOR", "SYSTEM_ADMIN")) {
            var other = auth("privacy-other-" + UUID.randomUUID(), role);
            mvc.perform(get("/api/v1/procurement/materials/{id}", id).with(authentication(other)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.data").isEmpty());
            mvc.perform(post("/api/v1/procurement/materials/{id}/retry", id).with(authentication(other)).with(csrf()))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/procurement/materials").with(authentication(other)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
            mvc.perform(get("/api/v1/procurement/materials/operations/{key}", key).with(authentication(other)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
            mvc.perform(get("/api/v1/procurement/projects/source-projects").with(authentication(other)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
            var command = Map.of("sourceProjectId", id, "departmentName", "测试部", "procurementType", "货物",
                    "budgetAmount", "100.00", "subjectCode", "A1", "subjectName", "设备",
                    "procurementMethod", "公开招标", "applicantName", "测试员");
            mvc.perform(post("/api/v1/procurement/projects").contentType("application/json")
                    .content(json.writeValueAsString(command)).with(authentication(other)).with(csrf()))
                    .andExpect(status().isUnprocessableEntity());
            var owned = service.uploadAsync("本人.docx", new java.io.ByteArrayInputStream(file().getBytes()),
                    UUID.randomUUID().toString(), (CurrentUser) other.getPrincipal());
            owned = owned.claim(UUID.randomUUID().toString(), now.plusSeconds(120), now);
            org.assertj.core.api.Assertions.assertThat(repository.claimPending(owned, now)).isTrue();
            org.assertj.core.api.Assertions.assertThat(repository.complete(owned.finish("PARSED", fields, "TEST", null, now))).isTrue();
            var supplementary = new java.util.HashMap<String, Object>(command);
            supplementary.put("sourceProjectId", owned.id().toString());
            supplementary.put("sourceMaterialIds", List.of(owned.id().toString(), id));
            mvc.perform(post("/api/v1/procurement/projects").contentType("application/json")
                    .content(json.writeValueAsString(supplementary)).with(authentication(other)).with(csrf()))
                    .andExpect(status().isUnprocessableEntity());
        }
        verifyNoInteractions(ai);
    }
    @Autowired com.fuquan.audit.common.material.application.port.MaterialIntakeRepository materialRepository;
    @Test void highlyCompressedContainerFailsBeforeAiAndStillReopens() throws Exception {
        byte[] bytes;
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("A".repeat(100000));
            document.write(output); bytes = output.toByteArray();
        }
        var uploader = auth("compressed-owner", "PROCUREMENT_APPLICANT");
        String key = UUID.randomUUID().toString();
        var response=mvc.perform(multipart("/api/v1/procurement/materials").file(new MockMultipartFile("file", "large.docx", "application/octet-stream", bytes))
                .header("Idempotency-Key", key).with(authentication(uploader)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED")).andReturn();
        String id=json.readTree(response.getResponse().getContentAsString()).path("data").path("id").asText();
        service.processPending(UUID.fromString(id),(CurrentUser)uploader.getPrincipal(),r -> {});
        mvc.perform(get("/api/v1/procurement/materials/operations/{key}", key).with(authentication(uploader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FAILED"));
        verifyNoInteractions(ai);
    }
}
