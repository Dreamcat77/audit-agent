package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real curl + HTTP session + scheduled worker; only semantic extraction and local persistence are simulated. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "server.address=127.0.0.1", "audit.procurement.auto-case.enabled=true",
        "audit.procurement.auto-case.scan-delay-ms=100"})
@ActiveProfiles("local")
class AutomaticCaseHttpTest {
    private static final String PASSWORD=UUID.randomUUID().toString();
    @DynamicPropertySource static void account(DynamicPropertyRegistry values) {
        values.add("audit.security.local-account.username",()->"automatic-http-test");
        values.add("audit.security.local-account.password-hash",()->PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
        values.add("audit.security.local-account.roles",()->"AUDITOR");
        values.add("audit.file-storage.root",()->Path.of("target","automatic-http-storage").toAbsolutePath().toString());
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @MockitoBean MaterialFieldAiPort ai;
    @MockitoBean CaseReviewAiPort reviewAi;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AuditCaseService cases;
    private Path cookies;
    private String csrf;

    @Test void uploadOnlyAutomaticallyFinishesAndCanBeRediscoveredOverRealHttp()throws Exception {
        String fixtures=System.getenv("AUDIT_CASE_FIXTURES");
        Assumptions.assumeTrue(fixtures!=null,"Set AUDIT_CASE_FIXTURES for the 12-file HTTP scenario");
        cookies=Files.createTempFile(Path.of("target"),"automatic-http-",".cookies");
        try {
            doCallRealMethod().when(ai).extractWithContext(anyString(),anyString(),anyString(),anyList(),any());
            when(reviewAi.analyze(any())).thenAnswer(call->CaseReviewFixtures.result(call.getArgument(0)));
            when(ai.extract(eq("PROCUREMENT_AUTO"),anyString(),anyString(),anyList()))
                    .thenAnswer(call->AuditCaseIntegrationTest.sourceFixture(call.getArgument(3)));
            JsonNode health=call("/api/v1/health",null);
            assertThat(health.path("code").asInt()).isEqualTo(200);
            JsonNode unauthenticated=call("/api/v1/procurement/cases",null);
            assertThat(unauthenticated.path("code").asInt()).isNotEqualTo(200);
            csrf=call("/api/v1/auth/csrf",null).path("data").path("token").asText();
            var login=json.createObjectNode().put("username","automatic-http-test").put("password",PASSWORD);
            assertThat(call("/api/v1/auth/login",json.writeValueAsString(login),"-H","Content-Type: application/json","-H","X-CSRF-TOKEN: "+csrf).path("code").asInt()).isEqualTo(200);
            csrf=call("/api/v1/auth/csrf",null).path("data").path("token").asText();
            UUID operation=UUID.randomUUID();
            var args=new ArrayList<String>(List.of("-H","X-CSRF-TOKEN: "+csrf,"-H","Idempotency-Key: "+operation));
            List<Path> files;try(var stream=Files.list(Path.of(fixtures))){files=stream.sorted().toList();}
            assertThat(files).hasSize(12);
            for(var file:files){args.add("-F");args.add("files=@"+file.toAbsolutePath());}
            JsonNode uploaded=call("/api/v1/procurement/cases",null,args.toArray(String[]::new));
            assertThat(uploaded.path("code").asInt()).isEqualTo(200);
            String id=uploaded.path("data").path("id").asText();
            assertThat(id).isNotBlank();
            // No process()/scan() or business start endpoint: Spring's actual scheduled worker must finish.
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).untilAsserted(()->{
                var result=call("/api/v1/procurement/cases/"+id,null).path("data");
                assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
            });
            JsonNode completed=call("/api/v1/procurement/cases/"+id,null);
            var report=completed.path("data").path("report");
            assertThat(report.path("budget").decimalValue()).isEqualByComparingTo("222600");
            assertThat(report.path("awardAmount").decimalValue()).isEqualByComparingTo("212000");
            assertThat(report.path("offers")).hasSize(3);
            assertThat(report.path("calculation").path("averageQuote").decimalValue()).isEqualByComparingTo("216066.67");
            for(var offer:report.path("offers")) {
                assertThat(offer.path("lines")).hasSize(3);
                assertThat(offer.path("lineValidation").asText()).isEqualTo("VERIFIED");
                assertThat(call("/api/v1/procurement/cases/"+id+"/files/"+offer.path("documentId").asText()+"/evidence",null).path("data").path("facts")).isNotEmpty();
            }
            assertThat(call("/api/v1/procurement/cases/operations/"+operation,null).path("data").path("id").asText()).isEqualTo(id);
            assertThat(call("/api/v1/procurement/cases",null).path("data").toString()).contains(id);
            UUID changing=UUID.randomUUID();
            doThrow(new org.springframework.dao.ConcurrencyFailureException("adversarial continuous writer"))
                    .when(cases).owned(eq(changing),any());
            assertThat(call("/api/v1/procurement/cases/"+changing,null).path("code").asInt()).isEqualTo(409);
            assertThat(call("/api/v1/procurement/cases/"+id,null).path("data").path("status").asText()).isEqualTo("COMPLETED");
            var evidence=json.createObjectNode().put("environment","local / real curl / real scheduler / simulated semantic extraction / in-memory repository")
                    .put("command","curl.exe -sS --max-time 30 -b <ephemeral-cookie-file> -c <ephemeral-cookie-file> -H X-CSRF-TOKEN:<redacted> -H Idempotency-Key:<operation> -F files=@<file> ... http://127.0.0.1:<random-port>/api/v1/procurement/cases");
            evidence.set("health",health);evidence.set("upload",uploaded);evidence.set("result",completed);
            Files.writeString(Path.of("target","automatic-case-http-evidence.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        } finally {Files.deleteIfExists(cookies);}
    }
    private JsonNode call(String path,String body,String... options)throws Exception {
        var args=new ArrayList<String>(List.of("curl.exe","-sS","--max-time","30","-b",cookies.toString(),"-c",cookies.toString()));
        args.addAll(List.of(options));
        if(body!=null)args.addAll(List.of("--data-binary","@-"));
        args.add("http://127.0.0.1:"+port+path);
        var process=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try(var input=process.getOutputStream()){if(body!=null)input.write(body.getBytes(StandardCharsets.UTF_8));}
        String response=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertThat(process.waitFor()).isZero();
        return json.readTree(response);
    }
}
