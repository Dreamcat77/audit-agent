import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Read only the synthetic IDs produced by this deployment's own HTTP probes. */
public final class MaterialDeploymentAcceptanceCheck {
    static void need(boolean value,String reason){if(!value)throw new IllegalStateException(reason);}
    public static void main(String[] args)throws Exception {
        var root=Path.of(args[0]);var json=new ObjectMapper();
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
            var budget=json.readTree(Files.readString(root.resolve("reliability-budget-results.json")));
            checkUnit(c,"BUDGET",budget.get("segmentedId").asText(),2);
            int procurement=0,reports=0;
            try(var files=Files.list(root)) {
                for(var f:files.filter(p->p.getFileName().toString().matches("[0-9a-f]{8}-procurement-(normal|risk)-intake\\.json")).toList()) {
                    var intake=json.readTree(Files.readString(f));checkUnit(c,"PROCUREMENT",intake.get("id").asText(),1);procurement++;
                }
            }
            try(var files=Files.list(root)) {
                for(var f:files.filter(p->p.getFileName().toString().matches("[0-9a-f]{8}-summary\\.json")).toList()) {
                    for(var row:json.readTree(Files.readString(f))) {
                        if(!row.has("reportId"))continue;
                        try(var p=c.prepareStatement("SELECT PROJECT_ID,AGENT_REVIEW_JSON,REPORT_SNAPSHOT_JSON FROM PROCUREMENT_REVIEW_REPORT WHERE ID=?")) {
                            p.setQueryTimeout(15);p.setString(1,row.get("reportId").asText());
                            try(var r=p.executeQuery()) {
                                need(r.next(),"SYNTHETIC_REPORT_MISSING");String raw=r.getString(3);
                                need(raw!=null&&!raw.isBlank(),"FULL_REPORT_NOT_PERSISTED");
                                var snapshot=json.readTree(raw);var data=snapshot.get("data");
                                need(snapshot.get("snapshotVersion").asInt()==1,"SNAPSHOT_VERSION");
                                need(data.get("project").get("id").asText().equals(row.get("projectId").asText()),"REPORT_PROJECT_IDENTITY");
                                need(data.get("agentReview").equals(json.readTree(r.getString(2))),"LEGACY_AND_FULL_AGENT_MISMATCH");
                                need(snapshot.get("materials").size()>0,"REPORT_MATERIAL_VERSIONS_MISSING");
                                if(row.get("case").asText().equals("procurement-risk")) {
                                    need(data.get("tenderFindings").size()>0,"TENDER_CHAPTER_MISSING");
                                    need(!data.get("quoteCheck").isNull()&&!data.get("collusionAssessment").isNull(),"PRICE_OR_COLLUSION_CHAPTER_MISSING");
                                }
                                reports++;
                            }
                        }
                    }
                }
            }
            need(procurement==2&&reports==2,"HTTP_PROBE_RECORDS_INCOMPLETE");
            System.out.println("DEPLOYED_DM8_EXTRACTION_COMPLETE_AND_FULL_REPORTS=PASS procurement="+procurement+" reports="+reports);
        } catch(SQLException failure){System.err.println("DEPLOYMENT_ACCEPTANCE_DB_FAILED dbCode="+failure.getErrorCode());System.exit(1);}
    }
    static void checkUnit(Connection c,String kind,String material,int minUnits)throws Exception {
        UUID operation=UUID.nameUUIDFromBytes(("material-extraction/"+kind+"/"+material).getBytes(StandardCharsets.UTF_8));
        try(var p=c.prepareStatement("SELECT STATUS,UNIT_COUNT,NEXT_UNIT,CALL_COUNT,USED_MILLIS FROM MATERIAL_EXTRACTION_LEDGER WHERE ID=?")) {
            p.setQueryTimeout(15);p.setString(1,operation.toString());
            try(var r=p.executeQuery()) {
                need(r.next(),"SYNTHETIC_LEDGER_MISSING");
                need(r.getString(1).equals("COMPLETE")&&r.getInt(2)>=minUnits&&r.getInt(2)==r.getInt(3),"SYNTHETIC_UNIT_NOT_COMPLETE");
                need(r.getInt(4)>0&&r.getInt(4)<=512&&r.getLong(5)>0&&r.getLong(5)<=900000,"SYNTHETIC_USAGE_NOT_PERSISTED");
                System.out.println("DEPLOYED_SYNTHETIC_LEDGER kind="+kind+" units="+r.getInt(2)+" calls="+r.getInt(4)+" cumulativeMillis="+r.getLong(5)+" result=PASS");
            }
        }
    }
}
