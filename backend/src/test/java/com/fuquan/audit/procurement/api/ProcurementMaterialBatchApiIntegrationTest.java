package com.fuquan.audit.procurement.api;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.procurement.application.port.*;
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
import org.springframework.test.web.servlet.MockMvc;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("local")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ProcurementMaterialBatchApiIntegrationTest {
    static final String PROJECT="00000000-0000-0000-0000-000000000101";
    static final String ROOT="/api/v1/procurement/projects/"+PROJECT+"/review-materials";
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired ProcurementCollusionRepository bidders;
    @Autowired ProcurementTenderDocumentRepository sources;
    @Autowired com.fuquan.audit.common.material.application.port.MaterialIntakeRepository intakes;
    @Autowired com.fuquan.audit.contract.application.port.FileStoragePort storage;
    @Autowired com.fuquan.audit.procurement.application.ProcurementProjectQueryService projects;

    @Test void savedIntakeCreatesReviewWithoutAnotherUploadAndReopensSameReport() throws Exception {
        var source = UUID.randomUUID();
        var content = file("需求与招标.docx", "采购需求及招标文件\n项目名称：自动审查仿真项目\n预算金额：100000元\n台式计算机必须使用星桥品牌，仅限星桥品牌参与。");
        var staged = storage.stage(content.getInputStream(), 1024*1024);
        var key = new com.fuquan.audit.contract.application.port.StorageKey(staged.sha256());
        storage.promote(staged,key);
        var now = java.time.Instant.now();
        var fields = new com.fuquan.audit.common.material.domain.MaterialIntakeFields("自动审查仿真项目", "TEST-AUTO",
            "测试部", "货物", new java.math.BigDecimal("100000"), "A1001", "办公设备", "公开招标", "采购测试设备", "测试员",
            Map.of(), List.of(), "fake", "test");
        intakes.insert(new com.fuquan.audit.common.material.domain.MaterialIntake(source,"batch-actor",source.toString(),
            content.getOriginalFilename(),content.getContentType(),staged.size(),staged.sha256(),key.value(),"PARSED",null,
            now,fields,"DOCX_TEXT",null,now,now));
        var payload = new HashMap<String,Object>();
        payload.put("sourceProjectId",source); payload.put("sourceMaterialIds",List.of(source));
        payload.put("projectName","自动审查仿真项目"); payload.put("departmentName","人工核对后的测试部");
        payload.put("procurementType","货物"); payload.put("budgetAmount",100000);
        payload.put("subjectCode","A1001"); payload.put("subjectName","办公设备");
        payload.put("procurementMethod","公开招标"); payload.put("applicantName","测试员");
        var created = mvc.perform(post("/api/v1/procurement/projects").with(authentication(auth("PROCUREMENT_MANAGER")))
            .with(csrf()).contentType("application/json").content(json.writeValueAsBytes(payload)))
            .andExpect(status().isOk())
            .andReturn();
        String project = json.readTree(created.getResponse().getContentAsString()).at("/data/id").asText();
        assertThat(projects.requireById(UUID.fromString(project)).departmentName()).isEqualTo("人工核对后的测试部");
        String root = "/api/v1/procurement/projects/"+project+"/review-materials";
        var analyzed = mvc.perform(post(root+"/from-intake/analyze").with(authentication(auth("PROCUREMENT_MANAGER"))).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.files.length()").value(1))
            .andExpect(jsonPath("$.data.report.id").isNotEmpty()).andReturn();
        var result = json.readTree(analyzed.getResponse().getContentAsString()).get("data");
        String reportId = result.at("/report/id").asText(), materialId = result.at("/files/0/document/id").asText();
        assertThat(sources.findById(UUID.fromString(materialId)).orElseThrow().storageKey()).isEqualTo(key.value());
        mvc.perform(post(root+"/from-intake/analyze").with(authentication(auth("PROCUREMENT_MANAGER"))).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.files[0].document.id").value(materialId))
            .andExpect(jsonPath("$.data.report.id").value(reportId));
        mvc.perform(get("/api/v1/procurement/projects/"+project+"/review-report").with(authentication(auth("AUDITOR"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(reportId));
        // Refresh is read-only; anti-CSRF and existing review roles remain enforced.
        mvc.perform(get(root+"/from-intake/analyze").with(authentication(auth("PROCUREMENT_MANAGER"))))
            .andExpect(status().isMethodNotAllowed());
        mvc.perform(post(root+"/from-intake/analyze").with(authentication(auth("PROCUREMENT_MANAGER"))))
            .andExpect(status().isForbidden());
        mvc.perform(post(root+"/from-intake/analyze").with(authentication(auth("PROCUREMENT_APPLICANT"))).with(csrf()))
            .andExpect(status().isForbidden());
    }
    static UsernamePasswordAuthenticationToken auth(String role) {
        return new UsernamePasswordAuthenticationToken(new CurrentUser("batch-actor","batch","审查员",Set.of(role)),null,
                List.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }
    static MockMultipartFile file(String name,String text) throws Exception {
        try(var doc=new XWPFDocument();var output=new ByteArrayOutputStream()) {
            for(String line:text.split("\n"))doc.createParagraph().createRun().setText(line);
            doc.write(output);return new MockMultipartFile("files",name,"application/vnd.openxmlformats-officedocument.wordprocessingml.document",output.toByteArray());
        }
    }
    @Test void oneMultipartBatchRoutesBiddersAutomaticallyAndReopensWithoutDuplicateDocuments() throws Exception {
        var first=file("投标文件甲.docx","办公采购投标文件\n投标人：甲科技\n投标报价为人民币 100.00 元\n办公设备提供安装培训保修服务。");
        var second=file("投标文件乙.docx","办公采购投标文件\n投标人：乙科技\n投标报价为人民币 101.00 元\n办公设备提供安装培训保修服务。");
        var third=file("投标文件丙.docx","办公采购投标文件\n投标人：丙科技\n投标报价为人民币 102.00 元\n办公设备提供安装培训保修服务。");
        var result=mvc.perform(multipart(ROOT+"/batch").file(first).file(second).file(third).with(authentication(auth("AUDITOR"))).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.files.length()").value(3))
                .andExpect(jsonPath("$.data.files[0].kind").value("BIDDER"))
                .andExpect(jsonPath("$.data.collusion.similarities.length()").value(3))
                .andExpect(jsonPath("$.data.quote.quotes.length()").value(3)).andReturn();
        String quoteId=json.readTree(result.getResponse().getContentAsString()).path("data").path("quote").path("id").asText();
        mvc.perform(multipart(ROOT+"/batch").file(first).file(second).file(third).with(authentication(auth("AUDITOR"))).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.quote.id").value(quoteId));
        assertThat(bidders.findDocuments(UUID.fromString(PROJECT))).hasSize(3);
        assertThat(sources.findByProjectId(UUID.fromString(PROJECT))).hasSize(3);
        mvc.perform(get(ROOT+"/current").with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.files.length()").value(3))
                .andExpect(jsonPath("$.data.collusion.similarities.length()").value(3))
                .andExpect(jsonPath("$.data.quote.id").value(quoteId)).andExpect(jsonPath("$.data.blacklist.totalCount").value(3));
    }
    @Test void csrfAndRoleChecksPrecedeMaterialParsing() throws Exception {
        var f=file("材料.docx","未授权材料");
        mvc.perform(multipart(ROOT+"/batch").file(f).with(authentication(auth("AUDITOR")))).andExpect(status().isForbidden());
        mvc.perform(multipart(ROOT+"/batch").file(f).with(authentication(auth("CONTRACT_MANAGER"))).with(csrf())).andExpect(status().isForbidden());
        var x=new MockMultipartFile("files","a.xlsx","application/octet-stream",new byte[]{1,2,3});
        mvc.perform(multipart(ROOT+"/batch").file(x).with(authentication(auth("PROCUREMENT_APPLICANT"))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(multipart(ROOT+"/uploads").file(f).with(authentication(auth("AUDITOR")))).andExpect(status().isForbidden());
        mvc.perform(post(ROOT+"/analyze").contentType("application/json").content("{\"sourceIds\":[]}")
                .with(authentication(auth("PROCUREMENT_APPLICANT"))).with(csrf())).andExpect(status().isForbidden());
    }
    @Test void eachUploadedBatchAutomaticallyAnalyzesAndPersistsItsOwnFrozenReport() throws Exception {
        var tender=file("招标文件.docx","招标文件\n采购项目名称：办公设备采购\n最高限价：180000元\n供货期30天，验收按采购清单数量及规格逐项核对，所有报价包含税费和安装。\n供应商依法参与竞争并提供履行合同所需资料。");
        var initial=mvc.perform(multipart(ROOT+"/uploads").file(tender).with(authentication(auth("AUDITOR"))).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.files.length()").value(1))
                .andExpect(jsonPath("$.data.files[0].status").value("INCOMPLETE"))
                .andExpect(jsonPath("$.data.report.id").isNotEmpty()).andExpect(jsonPath("$.data.quote").isEmpty()).andReturn();
        String initialReport=json.readTree(initial.getResponse().getContentAsString()).at("/data/report/id").asText();
        assertThat(bidders.findDocuments(UUID.fromString(PROJECT))).isEmpty();
        mvc.perform(get("/api/v1/procurement/projects/"+PROJECT+"/review-report").with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(initialReport));
        var first=file("投标文件甲.docx","投标文件\n投标人：甲设备\n投标报价：人民币100.00元\n提供二十套办公设备，按清单配送安装并提供三年保修服务。");
        var second=file("投标文件乙.docx","投标文件\n投标人：乙设备\n投标报价：人民币120.00元\n预约配送办公设备并完成现场调试，配置满足采购清单要求，安排技术支持。");
        var third=file("投标文件丙.docx","投标文件\n投标人：丙设备\n投标报价：人民币150.00元\n对设备执行出厂检验及资产登记，提供操作培训和故障维修保障。");
        mvc.perform(multipart(ROOT+"/uploads").file(first).file(second).file(third).with(authentication(auth("AUDITOR"))).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.files.length()").value(4))
                .andExpect(jsonPath("$.data.collusion.similarities.length()").value(3))
                .andExpect(jsonPath("$.data.quote.quotes.length()").value(3))
                .andExpect(jsonPath("$.data.report.id").isNotEmpty());
        var reopened=mvc.perform(get(ROOT+"/current").with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.files.length()").value(4)).andReturn();
        var ids=new ArrayList<String>();
        json.readTree(reopened.getResponse().getContentAsString()).path("data").path("files").forEach(f->ids.add(f.path("document").path("id").asText()));
        var body=json.writeValueAsString(Map.of("sourceIds",ids));
        var analyzed=mvc.perform(post(ROOT+"/analyze").contentType("application/json").content(body).with(authentication(auth("AUDITOR"))).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.quote.quotes.length()").value(3))
                .andExpect(jsonPath("$.data.collusion.similarities.length()").value(3))
                .andExpect(jsonPath("$.data.report.id").isNotEmpty()).andReturn();
        var reportId=json.readTree(analyzed.getResponse().getContentAsString()).path("data").path("report").path("id").asText();
        assertThat(reportId).isNotEqualTo(initialReport);
        mvc.perform(post(ROOT+"/analyze").contentType("application/json").content(body).with(authentication(auth("AUDITOR"))).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.report.id").value(reportId));
        mvc.perform(get("/api/v1/procurement/projects/"+PROJECT+"/review-report").with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(reportId));
        var extra=file("补充招标文件.docx","招标文件\n采购补充要求\n所有设备提供三年保修，现场按配置和数量执行验收，并登记设备清单，交付期三十个日历日。");
        mvc.perform(multipart(ROOT+"/uploads").file(extra).with(authentication(auth("AUDITOR"))).with(csrf())).andExpect(status().isOk());
        var latest=mvc.perform(get("/api/v1/procurement/projects/"+PROJECT+"/review-report").with(authentication(auth("AUDITOR"))))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(latest.getResponse().getContentAsString()).at("/data/id").asText()).isNotEqualTo(reportId);
        mvc.perform(post(ROOT+"/analyze").contentType("application/json").content(body).with(authentication(auth("AUDITOR"))).with(csrf()))
                .andExpect(status().isConflict());
    }
}
