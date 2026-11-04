import com.fasterxml.jackson.databind.*;
import com.fuquan.audit.procurement.autocase.*;
import com.fuquan.audit.procurement.application.*;
import com.fuquan.audit.procurement.application.port.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import java.nio.file.*;
import java.time.*;
import java.util.*;
/** Read-only request reconstruction from preserved, verified source evidence; not an E2E acceptance. */
public class CaseContextProbe {
    public static void main(String[] args)throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();var view=json.readTree(Path.of(args[0]).toFile()).get("data");
        var sources=json.readTree(Path.of(args[1]).toFile());var docs=new ArrayList<AuditCase.Document>();
        for(var d:view.get("documents"))for(var e:sources)if(e.get("data").get("fileName").asText().equals(d.get("name").asText())) {
            var s=e.get("data");var segments=new ArrayList<MaterialFieldAiPort.Segment>();var facts=new ArrayList<AuditCase.Fact>();
            for(var t:s.get("segments"))segments.add(json.treeToValue(t,MaterialFieldAiPort.Segment.class));
            for(var f:s.get("facts"))facts.add(json.treeToValue(f,AuditCase.Fact.class));
            docs.add(new AuditCase.Document(UUID.fromString(d.get("id").asText()),d.get("name").asText(),1,s.get("sha256").asText(),"read-only-probe","EXTRACTED",segments,facts,"SAVED_EVIDENCE",null,1));
        }
        var rules=(ProcurementTenderRuleRepository)java.lang.reflect.Proxy.newProxyInstance(CaseContextProbe.class.getClassLoader(),new Class[]{ProcurementTenderRuleRepository.class},(proxy,method,values)->TenderRuleCatalog.defaults(Instant.EPOCH));
        try(var engine=new EngineClose(new CaseReviewEngine(r->{throw new UnsupportedOperationException();},rules,Clock.systemUTC(),1000))) {
            for(String module:List.of("AGENT_PRECHECK","TENDER_COMPLIANCE")) {
                var request=engine.value.prepare(UUID.fromString(view.get("id").asText()),module,docs);
                Files.writeString(Path.of(args[2],module+"-request.json"),json.writeValueAsString(request));
                System.out.println(module+" chars="+request.evidence().stream().mapToInt(e->e.excerpt().length()).sum()+" documents="+request.documents().size());
            }
        }
    }
    record EngineClose(CaseReviewEngine value) implements AutoCloseable {public void close(){value.close();}}
}
