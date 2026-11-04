package com.fuquan.audit.contract.application.debug;

import com.fasterxml.jackson.databind.*;
import com.fuquan.audit.contract.application.clause.*;
import com.fuquan.audit.contract.application.project.*;
import com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateAssembler;
import com.fuquan.audit.contract.application.project.identity.DeterministicProjectIdentityResolver;
import com.fuquan.audit.contract.application.project.parse.*;
import com.fuquan.audit.contract.application.project.role.*;
import com.fuquan.audit.contract.application.reviewrun.*;
import com.fuquan.audit.contract.domain.*;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.contract.domain.rule.AwardAmountMatchRule;
import com.fuquan.audit.review.domain.RiskLevel;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Test-only compiled adapters execute Java-owned capabilities, exporting synthetic observations only. */
class JavaQualityHarnessTest {
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Instant NOW=Instant.parse("2026-09-16T00:00:00Z");
    private static final UUID PROJECT=UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID FILE=UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID PARSE=UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static String hash(String input) { return ContractSnapshotCanonicalizer.sha256(input); }

    @Test void executeVersionedFixtureManifestThroughRealJavaCapabilities() throws Exception {
        var fixture=JSON.readTree(Files.readString(Path.of("../quality/fixtures/synthetic-v1.json")));
        assertThat(fixture.path("source").asText()).isEqualTo("SYNTHETIC");
        List<Map<String,Object>> observations=new ArrayList<>();
        for(var item:fixture.path("cases")) {
            String capability=item.path("capability").asText(),input=item.path("input").asText();
            if(Set.of("OCR","SEMANTIC").contains(capability)) continue;
            assertThat(hash(input)).isEqualTo(item.path("inputSha256").asText());
            long started=System.nanoTime();
            Map<String,Object> observed=execute(capability,input);
            observed.put("caseId",item.path("id").asText());
            observed.put("durationMs",(System.nanoTime()-started)/1_000_000L);
            observed.put("schemaValid",true);observed.put("supported",true);observed.putIfAbsent("bindingValid",true);
            observed.putIfAbsent("locatorHit",true);observed.put("failure",false);observed.put("timeout",false);observed.put("retries",0);
            observations.add(observed);
        }
        assertThat(observations).hasSize(8);
        assertThat(observations.stream().filter(o -> o.get("caseId").equals("candidate-1")).findFirst().orElseThrow().get("text")).isEqualTo("SYN-CONTRACT");
        assertThat(observations.stream().filter(o -> o.get("caseId").equals("f02-1")).findFirst().orElseThrow().get("label")).isEqualTo("PASS");
        assertThat(observations.stream().filter(o -> o.get("caseId").equals("identity-1")).findFirst().orElseThrow().get("label")).isEqualTo("CONFLICT");
        assertThat(observations.stream().filter(o -> o.get("caseId").equals("evidence-1")).findFirst().orElseThrow().get("label")).isEqualTo("3:5");
        Path output=Path.of("target/quality-java-observations.json");
        Files.writeString(output,JSON.writeValueAsString(observations),StandardCharsets.UTF_8);
    }
    private Map<String,Object> execute(String capability,String input) throws Exception {
        Map<String,Object> result=new TreeMap<>();
        switch(capability) {
            case "PARSE" -> {
                byte[] bytes;
                try(var document=new XWPFDocument();var output=new ByteArrayOutputStream()) {
                    document.createParagraph().createRun().setText(input);document.write(output);bytes=output.toByteArray();
                }
                var config=ProjectIntakeProperties.defaults();
                var parser=new ProjectDocxTextParser(new ProjectDocxContainerGuard(new SelectedProjectArchiveReader(config),config));
                result.put("text",parser.parse(file(input),new ByteArrayInputStream(bytes)).fullText().strip());
            }
            case "DOCUMENT_ROLE" -> result.put("label",new DeterministicDocumentRoleResolver()
                    .resolve(new DocumentRoleSignalExtractor().extract(parse(input))).proposedRole().name());
            case "PROJECT_IDENTITY" -> {
                var project=new Project(PROJECT,"Synthetic Project","SYN-002",null,"synthetic",1,NOW,NOW);
                var facts=List.of(fact("PROJECT_NO",input));
                result.put("label",new DeterministicProjectIdentityResolver().resolve(project,file(input),parse(input),facts,
                        UUID.randomUUID(),hash("synthetic-identity"),NOW).assessment().status().name());
            }
            case "CONTRACT_CANDIDATE" -> result.put("text",new ContractCandidateAssembler(Clock.fixed(NOW,ZoneOffset.UTC))
                    .assemble(PROJECT,file(input),List.of(fact("CONTRACT_NO",input))).contractNo());
            case "F02" -> {
                UUID contractId=UUID.randomUUID();
                var award=new AwardNoticeReference(UUID.randomUUID(),contractId,"SYN-AWARD","Synthetic Project","Synthetic Vendor",null,
                        Money.of(new BigDecimal("100.0")),LocalDate.of(2026,1,1),null);
                var contract=new Contract(contractId,"SYN-CONTRACT","Synthetic Contract","SYNTHETIC","Synthetic A","Synthetic B",null,
                        Money.of(new BigDecimal(input)),null,"DRAFT",0,NOW,NOW,List.of(),award);
                var rule=new AwardAmountMatchRule();
                var config=new ContractRuleConfiguration(rule.ruleCode(),"synthetic",true,RiskLevel.RED,true,1,null,NOW);
                result.put("label",rule.evaluate(new ContractReviewContext(contract),config).get(0).checkStatus().name());
                result.put("amount",contract.contractAmount().amount().toPlainString());
            }
            case "BREACH" -> {
                var reviewed=new BreachClauseAnalyzer().analyze(new BigDecimal("100.00"),List.of(new ContractClauseMaterial(FILE,"synthetic",input)));
                result.put("claims",reviewed.items().stream().map(BreachClauseReview.Item::frequency).toList());
                result.put("amount",reviewed.items().get(0).correspondingAmount().toPlainString());
            }
            case "TERM" -> result.put("claims",new ContractTermClauseAnalyzer().analyze(List.of(new ContractClauseMaterial(FILE,"synthetic",input)))
                    .dates().stream().map(ContractTermClauseReview.DateItem::normalizedDate).toList());
            case "EVIDENCE_GROUNDING" -> {
                var material=new FrozenReviewMaterial(UUID.randomUUID(),1,FILE,hash("file"),PARSE,1,hash(input),input,
                        DocumentRole.CONTRACT_BODY,null,UUID.randomUUID(),null);
                var grounded=new FrozenEvidenceGrounder().ground(material,"违约",true);
                result.put("label",grounded.startOffset()+":"+grounded.endOffset());
                result.put("bindingValid",grounded.excerptSha256().equals(hash("违约")));result.put("locatorHit",grounded.locatorAvailable());
            }
            default -> throw new IllegalArgumentException("unsupported Java quality capability");
        }
        return result;
    }
    private ProjectFile file(String text) {
        return new ProjectFile(FILE,PROJECT,null,null,"synthetic.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ProjectFileCategory.UNKNOWN,ProjectFileIngestionStatus.AVAILABLE,ProjectFileParseStatus.PARSED,
                hash("file"),hash("file"),1024,text,null,NOW,NOW);
    }
    private ProjectFileParseResult parse(String text) {
        return new ProjectFileParseResult(PARSE,PROJECT,FILE,hash("file"),1,ProjectFileParseOrigin.CURRENT_PIPELINE,
                ProjectFileParseStatus.PARSED,ProjectFileParseMethod.DOCX_TEXT,"synthetic","v1",text,hash(text),1,1L,null,null,NOW,NOW,0);
    }
    private ExtractionResult fact(String type,String value) {
        return new ExtractionResult(UUID.randomUUID(),PROJECT,FILE,PARSE,null,hash(type+value),"synthetic-v1",type,null,value,value,null,
                value,1,1,"paragraph:1",NOW);
    }
}
