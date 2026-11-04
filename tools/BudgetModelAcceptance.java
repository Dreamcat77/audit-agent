import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.application.*;
import com.fuquan.audit.common.material.application.*;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import com.fuquan.audit.integration.ai.fastapi.FastApiMaterialFieldAdapter;
import com.fuquan.audit.integration.material.*;
import com.fuquan.audit.integration.material.memory.InMemoryMaterialExtractionLedger;
import com.fuquan.audit.integration.ocr.fastapi.FastApiOcrAdapter;
import org.springframework.web.client.RestClient;
import java.nio.file.*;
import java.util.*;

/** Isolated real-provider acceptance. Synthetic fixtures only; no application database writes. */
public class BudgetModelAcceptance {
    public record Suite(String label,List<Case> cases) {}
    public record Case(String label,List<String> files,String expectation) {}
    public record Output(String fileName,String method,int characters,SnapshotView extraction,Object result,String failureCode) {}
    public record SnapshotView(List<Row> facts,List<ExtractionIssue> issues,List<Result> acknowledgedModelResults) {}
    public static void main(String[] args)throws Exception {
        if(args.length!=4&&args.length!=5)throw new IllegalArgumentException("AI_URL OCR_URL MANIFEST OUTPUT [ACKNOWLEDGED_SYNTHETIC_REPLAY]");
        var json=new ObjectMapper();Path manifest=Path.of(args[2]).toRealPath(),root=manifest.getParent();
        var suite=json.readValue(Files.readString(manifest),Suite.class);
        boolean sourceOnly=args.length==5&&args[4].startsWith("source-only:");
        boolean capturedReplay=args.length==5&&args[4].startsWith("captured:");
        var captured=args.length==5?json.readTree(Files.readString(Path.of(sourceOnly?args[4].substring(12):capturedReplay?args[4].substring(9):args[4]))):null;
        var replay=sourceOnly?null:captured;
        if(!suite.label().equals("SYNTHETIC_BUDGET_ACCEPTANCE"))throw new IllegalArgumentException("SYNTHETIC_ONLY");
        var config=new MaterialParsingConfiguration();
        var guard=MaterialParsingConfiguration.class.getDeclaredMethod("procurementDocxGuard");guard.setAccessible(true);
        var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(5));factory.setReadTimeout(java.time.Duration.ofSeconds(300));
        var parser=new OfficeDocumentTextReader((MaterialContainerPort)guard.invoke(config),
            new FastApiOcrAdapter(RestClient.builder().baseUrl(args[1]).requestFactory(factory).build()));
        var transport=new FastApiMaterialFieldAdapter(RestClient.builder().baseUrl(args[0]).build());
        var output=new ArrayList<Map<String,Object>>();
        for(var test:suite.cases()) {
            var inputs=new ArrayList<BudgetUploadedFacts.Input>();
            var details=new LinkedHashMap<UUID,Map<String,Object>>();
            for(String file:test.files()) {
                Path path=root.resolve(file).toRealPath();
                if(!path.startsWith(root))throw new IllegalArgumentException("FIXTURE_BOUNDARY");
                UUID id=UUID.randomUUID();String name=path.getFileName().toString();
                var detail=new LinkedHashMap<String,Object>();detail.put("fileName",name);detail.put("taskId",id);
                long started=System.nanoTime();
                var models=new ArrayList<Result>();
                var durable=new DurableMaterialExtractionService(new InMemoryMaterialExtractionLedger(),transport,json);
                MaterialFieldAiPort model=(kind,material,sha,segments)->{
                    if(replay!=null) {
                        for(var prior:replay)if(prior.path("label").asText().equals(test.label()))for(var previous:prior.path("files"))
                            if(previous.path("fileName").asText().equals(name)) {
                                var acknowledged=previous.path("extraction").path("acknowledgedModelResults");
                                if(acknowledged.size()!=1)throw new IllegalStateException("REPLAY_UNAVAILABLE");
                                var result=json.convertValue(acknowledged.get(0),Result.class);models.add(result);return result;
                            }
                        throw new IllegalStateException("REPLAY_UNAVAILABLE");
                    }
                    var result=durable.extract(kind,material,sha,segments);models.add(result);return result;
                };
                try(var in=Files.newInputStream(path)) {
                    DocumentTextPort.Result document=null;
                    if(sourceOnly||capturedReplay) {
                        for(var prior:captured)if(prior.path("label").asText().equals(test.label()))for(var previous:prior.path("files"))
                            if(previous.path("fileName").asText().equals(name)&&previous.has("sourceText")) {
                                var segments=json.readValue(previous.path("sourceText").toString(),new com.fasterxml.jackson.core.type.TypeReference<List<Segment>>(){});
                                String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
                                if(previous.has("sourceSha256")&&!previous.path("sourceSha256").asText().equals(sha))throw new IllegalStateException("REPLAY_SOURCE_CHANGED");
                                document=new DocumentTextPort.Result(segments,previous.path("readMethod").asText(),sha);
                            }
                        if(document==null)throw new IllegalStateException("CAPTURED_TEXT_UNAVAILABLE");
                    } else document=parser.read(in,name,id.toString(),500L*1024*1024,100,192000);
                    if(replay!=null&&!capturedReplay) {
                        boolean matching=false;
                        for(var prior:replay)if(prior.path("label").asText().equals(test.label()))for(var previous:prior.path("files"))
                            if(previous.path("fileName").asText().equals(name)&&json.valueToTree(document.segments()).equals(previous.path("sourceText")))matching=true;
                        if(!matching)throw new IllegalStateException("REPLAY_SOURCE_CHANGED");
                    }
                    detail.put("readMethod",document.method());detail.put("sourceText",document.segments());
                    detail.put("sourceSha256",document.sha256());
                    detail.put("readMillis",java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
                    var snapshot=new BudgetUploadedMaterialReader(null,model,null).extract(document,id.toString());
                    inputs.add(new BudgetUploadedFacts.Input(id,name,"PARSED",snapshot));
                    detail.put("readMethod",document.method());detail.put("sourceText",document.segments());
                    detail.put("sourceSha256",document.sha256());detail.put("modelResultReused",replay!=null);
                    detail.put("sourceTextReused",sourceOnly||capturedReplay);
                    detail.put("extraction",new SnapshotView(snapshot.rows(),snapshot.issues(),models));
                    System.out.println("SYNTHETIC_FILE_END caseIndex="+(output.size()+1)+" rows="+snapshot.rows().size()+" issues="+snapshot.issues().size()+" modelOperations="+models.size());
                } catch(Exception failed) {
                    inputs.add(new BudgetUploadedFacts.Input(id,name,"FAILED",null));
                    detail.put("failureCode",BudgetProcessingOutcome.reason(failed));
                    System.out.println("SYNTHETIC_FILE_END caseIndex="+(output.size()+1)+" reasonCode="+BudgetProcessingOutcome.reason(failed));
                }
                detail.put("elapsedMillis",java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
                details.put(id,detail);
            }
            var results=new BudgetUploadedMaterialAnalyzer().analyze(inputs);
            details.forEach((id,detail)->detail.put("result",results.get(id)));
            output.add(Map.of("label",test.label(),"expectation",test.expectation(),"files",details.values()));
            Files.writeString(Path.of(args[3]),json.writerWithDefaultPrettyPrinter().writeValueAsString(output));
            System.out.println("SYNTHETIC_CASE_SAVED caseIndex="+output.size()+" files="+details.size());
        }
        System.out.println("SYNTHETIC_BUDGET_ACCEPTANCE_DONE cases="+output.size()+" businessWrites=0");
    }
}
