import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.application.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.Result;
import com.fuquan.audit.integration.material.OfficeDocumentTextReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Read-only revalidation of acknowledged model results. No model replay or task mutation. */
public final class BudgetEvidenceReplayCheck {
    public static void main(String[] ids)throws Exception {
        if(ids.length==0||ids.length>20)throw new IllegalArgumentException("IDS_REQUIRED");
        Path root=Path.of(System.getenv("AUDIT_FILE_STORAGE_ROOT"),"budget-materials").toRealPath();
        var json=new ObjectMapper();int passed=0,skipped=0,rejected=0;
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
            c.setReadOnly(true);
            for(String value:ids) {
                UUID id=UUID.fromString(value),operation=UUID.nameUUIDFromBytes(("material-extraction/BUDGET/"+id).getBytes(StandardCharsets.UTF_8));
                try(var p=c.prepareStatement("SELECT STATUS,RESULTS_JSON,SOURCE_HASH FROM MATERIAL_EXTRACTION_LEDGER WHERE ID=?")) {
                    p.setQueryTimeout(15);p.setString(1,operation.toString());
                    try(var r=p.executeQuery()) {
                        if(!r.next()||!r.getString(1).equals("COMPLETE")){skipped++;System.out.println("REPLAY_SKIP id="+id+" reasonCode=NO_ACKNOWLEDGED_COMPLETE_RESULT");continue;}
                        var units=json.readValue(r.getString(2),new TypeReference<List<Result>>(){});String hash=r.getString(3);
                        String name,reference;
                        try(var q=c.prepareStatement("SELECT FILE_NAME,TEMP_FILE_PATH FROM BUDGET_REVIEW_TASK WHERE ID=?")) {
                            q.setQueryTimeout(15);q.setString(1,id.toString());try(var row=q.executeQuery()) {
                                if(!row.next())throw new IllegalStateException("TASK_NOT_FOUND");name=row.getString(1);reference=row.getString(2);
                            }
                        }
                        if(reference==null||!reference.matches("[A-Za-z0-9._-]+"))throw new IllegalStateException("STORAGE_REFERENCE_INVALID");
                        Path source=root.resolve(reference).toRealPath();if(!source.getParent().equals(root))throw new IllegalStateException("STORAGE_BOUNDARY");
                        // Null OCR intentionally prevents a new OCR/model call during this read-only probe.
                        var reader=new OfficeDocumentTextReader(null,null);
                        try(var input=Files.newInputStream(source)) {
                            var document=reader.read(input,name,id.toString(),500L*1024*1024,100,192000);
                            var facts=units.stream().flatMap(unit->unit.rows().stream()).toList();
                            MaterialFieldAiPort cached=(kind,material,sha,segments)->{
                                try {
                                    // Ledger SOURCE_HASH covers the extraction strategy and semantic
                                    // source segments; it is not the uploaded file's SHA-256.
                                    String fingerprint=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(json.writeValueAsBytes(List.of("material-units-v1/core1600/context120/max6",kind,material,sha,segments))));
                                    if(!fingerprint.equals(hash))throw new IllegalStateException("SOURCE_FINGERPRINT_CHANGED");
                                    var texts=new HashMap<String,String>();segments.forEach(segment->texts.put(segment.locator(),segment.text()));
                                    var fields=Set.of("projectName","projectCode","subjectName","subjectCode","budgetYear","departmentName","declaredAmount","approvalAmount","supplierName");
                                    for(var fact:facts.stream().flatMap(row->row.facts().stream()).toList()) {
                                        String text=texts.get(fact.locator());
                                        if(text!=null&&fields.contains(fact.field())&&text.indexOf(fact.excerpt())!=text.lastIndexOf(fact.excerpt()))
                                            System.out.println("REPLAY_REPEATED_EVIDENCE id="+id+" field="+fact.field()+" excerptChars="+fact.excerpt().length());
                                    }
                                    return new Result(facts,units.get(0).model(),units.get(0).promptVersion());
                                } catch(java.security.NoSuchAlgorithmException|java.io.IOException failure){throw new IllegalStateException("REPLAY_FINGERPRINT_FAILED");}
                            };
                            var snapshot=new BudgetUploadedMaterialReader(null,cached,null).extract(document,id.toString());
                            var analyzed=new BudgetUploadedMaterialAnalyzer().analyze(List.of(new BudgetUploadedFacts.Input(id,name,"PARSED",snapshot))).get(id);
                            long amounts=analyzed.lines().stream().filter(line->line.declaredAmount()!=null||line.approvalAmount()!=null).count();
                            passed++;System.out.println("REPLAY_PASS id="+id+" rows="+snapshot.rows().size()+" issues="+snapshot.issues().size()
                                +" resultLines="+analyzed.lines().size()+" linesWithVerifiedAmount="+amounts);
                        } catch(Exception failure) {
                            var frame=com.fuquan.audit.common.observability.SafeExceptionDiagnostics.safeTopStackFrame(failure);
                            rejected++;System.out.println("REPLAY_REJECT id="+id+" reasonCode="+BudgetProcessingOutcome.reason(failure)
                                +" errorType="+failure.getClass().getSimpleName()+" stackMethod="+frame.methodName()+" stackLine="+frame.lineNumber());
                        }
                    }
                }
            }
        } catch(SQLException error){System.err.println("REPLAY_DB_FAILED dbCode="+error.getErrorCode());System.exit(1);}
        System.out.println("READ_ONLY_BUDGET_EVIDENCE_REPLAY passed="+passed+" skipped="+skipped+" rejected="+rejected+" modelCalls=0 businessWrites=0");
    }
}
