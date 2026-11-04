import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** SELECT-only inspection. Raw snapshots/text are read locally, never written to export. */
public final class FieldDiag {
    static final ObjectMapper JSON=new ObjectMapper();
    static final int CLOB_LIMIT=24_000_000;
    static final Set<String> FIELDS=Set.of("documentType","projectName","projectNo","purchaserName","agencyName","budgetAmount","ceilingAmount","supplierName","quoteAmount","awardAmount","productDescription","itemName","quantity","unitPrice","lineAmount","declaredAmount","approvalAmount","subjectCode","subjectName","CONTRACT_NO","CONTRACT_NAME","CONTRACT_TYPE","PROJECT_NAME","PROJECT_NO","PARTY_A","PARTY_B","TOTAL_AMOUNT","AMOUNT_WORDS","AWARD_AMOUNT","AWARD_SUPPLIER","AMOUNT_NUMERIC","AMOUNT_UPPERCASE");
    static final LinkedHashMap<String,Pattern> SIGNALS=new LinkedHashMap<>();
    static {
        SIGNALS.put("BUDGET",Pattern.compile("预算|核定经费|批准经费|批复金额|经费安排|资金预算|本项目.{0,8}经费"));
        SIGNALS.put("CEILING",Pattern.compile("最高限价|控制价|限价"));
        SIGNALS.put("PURCHASER",Pattern.compile("采购人|采购单位|招标人|委托人|委托方|需求单位|需求部门"));
        SIGNALS.put("AGENCY",Pattern.compile("招标代理|采购代理|受托方|受托人"));
        SIGNALS.put("SUPPLIER",Pattern.compile("供应商|投标人|中标人|成交单位|乙方|承包人"));
        SIGNALS.put("QUOTE",Pattern.compile("投标报价|报价总|报价合计|响应报价|含税总价|价税合计"));
        SIGNALS.put("AWARD",Pattern.compile("中标金额|成交金额|中标价|成交价"));
        SIGNALS.put("CONTRACT_TITLE",Pattern.compile("合同书|采购合同|服务合同|施工合同|委托合同|合同协议书"));
        SIGNALS.put("CONTRACT_AMOUNT",Pattern.compile("合同金额|合同总价|总价款|合同价款|签约合同价"));
        SIGNALS.put("CONTRACT_NUMBER",Pattern.compile("合同编号|合同号"));
        SIGNALS.put("PARTY_A",Pattern.compile("甲方|发包人|买方"));
        SIGNALS.put("PARTY_B",Pattern.compile("乙方|承包人|卖方"));
        SIGNALS.put("PROJECT_NAME",Pattern.compile("项目名称|工程名称|采购项目"));
        SIGNALS.put("PROJECT_NUMBER",Pattern.compile("项目编号|采购编号|招标编号"));
        SIGNALS.put("TITLE_REQUIREMENT",Pattern.compile("需求标准|采购需求表|需求说明|需求文件"));
        SIGNALS.put("TITLE_ENTRUSTMENT",Pattern.compile("采购委托协议|委托协议"));
        SIGNALS.put("TITLE_APPROVAL",Pattern.compile("审批表|审批单|预算通知书|预算批复"));
        SIGNALS.put("TITLE_TENDER",Pattern.compile("招标文件|竞争性磋商文件|采购文件"));
        SIGNALS.put("TITLE_AWARD",Pattern.compile("中标通知书|成交通知书|采购评标报告|评标报告"));
    }
    static final Pattern MONEY=Pattern.compile("(?:[0-9０-９][0-9０-９,，.． ]{0,35}(?:万|亿)?(?:元|圆|人民币)|人民币\\s*[0-9０-９][0-9０-９,，.． ]{0,35}|[壹贰叁肆伍陆柒捌玖拾佰仟萬億貳參陸零〇一二三四五六七八九十百千万亿]{2,}(?:元|圆|圓))");
    static final Set<String> VOCABULARY=new HashSet<>();
    static String code(String s){return s!=null&&VOCABULARY.contains(s)?s:"OTHER_OR_UNAVAILABLE";}
    static String uuid(String s){try{return UUID.fromString(s).toString();}catch(Exception e){return "UNAVAILABLE";}}
    static String text(JsonNode n,String k){return n.path(k).isTextual()?n.path(k).asText():"";}
    static String lob(ResultSet r,String col)throws Exception {
        try(Reader reader=r.getCharacterStream(col)){
            if(reader==null)return "";var out=new StringBuilder();char[] buf=new char[8192];int n;
            while((n=reader.read(buf))>=0){if(out.length()+n>CLOB_LIMIT)throw new IOException("CLOB_LIMIT");out.append(buf,0,n);}return out.toString();
        }
    }
    static Map<String,Object> signals(String value){
        var out=new LinkedHashMap<String,Object>();
        for(var signal:SIGNALS.entrySet()){
            Matcher m=signal.getValue().matcher(value);int count=0,near=0,first=-1;
            while(m.find()){if(first<0)first=m.start();count++;if(MONEY.matcher(value.substring(Math.max(0,m.start()-25),Math.min(value.length(),m.end()+100))).find())near++;}
            out.put(signal.getKey(),Map.of("count",count,"nearMoney",near,"firstOffset",first));
        }
        int money=0;var mm=MONEY.matcher(value);while(mm.find())money++;
        out.put("TOTAL_MONEY_LIKE_SPANS",money);return out;
    }
    static Map<String,Object> features(String value){
        long han=value.codePoints().filter(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN).count();
        long replacement=value.chars().filter(c->c==0xfffd).count();
        var out=new LinkedHashMap<String,Object>();out.put("chars",value.length());out.put("lines",value.isEmpty()?0:value.split("\\R",-1).length);
        out.put("hanChars",han);out.put("replacementChars",replacement);out.put("signals",signals(value));return out;
    }
    static Map<String,Object> pageCoverage(JsonNode segments) {
        var pages=new TreeMap<Integer,Map<String,Object>>();int unmapped=0;
        for(var segment:segments) {
            String locator=text(segment,"locator"),body=text(segment,"text");
            var match=Pattern.compile("^pdf/page:([0-9]{1,6})(?:/|$)").matcher(locator);
            if(!match.find()){unmapped++;continue;}
            int page=Integer.parseInt(match.group(1));if(page<1){unmapped++;continue;}
            var row=pages.computeIfAbsent(page,k->new LinkedHashMap<>(Map.of("page",k,"chars",0,"segments",0,"ocrChars",0,"nativeChars",0)));
            row.put("chars",(int)row.get("chars")+body.length());row.put("segments",(int)row.get("segments")+1);
            String kind=locator.contains("/ocr")?"ocrChars":"nativeChars";
            row.put(kind,(int)row.get(kind)+body.length());
        }
        var out=new LinkedHashMap<String,Object>();out.put("pagesWithSavedText",pages.size());out.put("unmappedSegments",unmapped);
        out.put("firstPage",pages.isEmpty()?null:pages.firstKey());out.put("lastPage",pages.isEmpty()?null:pages.lastKey());
        out.put("pages",pages.values());out.put("coverageComplete",null);
        out.put("coverageNote","SAVED_TEXT_PAGES_ONLY_COMPARE_WITH_PDF_INVENTORY");return out;
    }
    static Map<String,Object> counts(JsonNode items,String field){
        var result=new TreeMap<String,Integer>();for(var item:items){String type=text(item,field);String safe=FIELDS.contains(type)?type:"OTHER_FIELD";result.merge(safe,1,Integer::sum);}return new LinkedHashMap<>(result);
    }
    static Set<String> columns(Connection c,String table)throws Exception {
        Set<String> out=new HashSet<>();try(var p=c.prepareStatement("SELECT COLUMN_NAME FROM USER_TAB_COLUMNS WHERE TABLE_NAME=?")){p.setString(1,table);p.setQueryTimeout(15);try(var r=p.executeQuery()){while(r.next())out.add(r.getString(1));}}return out;
    }
    static List<JsonNode> snapshots(Connection c,String table,String where,List<String> parameters,int limit)throws Exception {
        if(!Set.of("AUDIT_CASE","AUDIT_CASE_FILE","CONTRACT_ANALYSIS_CASE").contains(table))throw new IllegalArgumentException();
        var out=new ArrayList<JsonNode>();try(var p=c.prepareStatement("SELECT SNAPSHOT_JSON FROM "+table+" WHERE "+where)){
            p.setQueryTimeout(30);p.setMaxRows(limit);for(int i=0;i<parameters.size();i++)p.setString(i+1,parameters.get(i));
            try(var r=p.executeQuery()){while(r.next())out.add(JSON.readTree(lob(r,"SNAPSHOT_JSON")));}
        }return out;
    }
    static List<Map<String,Object>> metadata(Connection c,String table,String allowed,String scope,List<String> params)throws Exception {
        var available=columns(c,table);if(available.isEmpty())return List.of(Map.of("tableState","NOT_PRESENT_OR_VISIBLE"));
        String scopeColumn=scope.split("=",2)[0];
        if(!available.contains(scopeColumn))return List.of(Map.of("tableState","SCOPE_COLUMN_UNAVAILABLE"));
        var cols=Arrays.stream(allowed.split(" ")).filter(available::contains).toList();if(cols.isEmpty())return List.of(Map.of("tableState","NO_COMPATIBLE_COLUMNS"));
        List<Map<String,Object>> result=new ArrayList<>();try(var p=c.prepareStatement("SELECT "+String.join(",",cols)+" FROM "+table+" WHERE "+scope)){
            p.setQueryTimeout(20);p.setMaxRows(201);for(int i=0;i<params.size();i++)p.setString(i+1,params.get(i));
            try(var r=p.executeQuery()){while(r.next()){
                var row=new LinkedHashMap<String,Object>();for(String col:cols){Object o=r.getObject(col);
                    if(o==null||o instanceof Number)row.put(col,o);
                    else if(col.equals("ID")||col.endsWith("_ID"))row.put(col,uuid(o.toString()));
                    else if(o instanceof Timestamp||o instanceof java.util.Date)row.put(col,o.toString());
                    else row.put(col,code(o.toString()));
                }result.add(row);
            }}
        }catch(SQLException e){return List.of(Map.of("tableState","QUERY_FAILED","dbCode",e.getErrorCode()));}return result;
    }
    static List<Map<String,Object>> inputs=new ArrayList<>();
    static List<Map<String,Object>> candidatePresence(Connection c,String project)throws Exception {
        String table="CONTRACT_CANDIDATE";var available=columns(c,table);
        if(!available.contains("PROJECT_ID"))return List.of();
        var selected=List.of("CONTRACT_NO","CONTRACT_NAME","CONTRACT_TYPE","PARTY_A","PARTY_B","CONTRACT_AMOUNT","AMOUNT_IN_WORDS","AWARD_AMOUNT","AWARD_SUPPLIER").stream().filter(available::contains).toList();
        if(selected.isEmpty())return List.of();var result=new ArrayList<Map<String,Object>>();
        try(var p=c.prepareStatement("SELECT ID,"+String.join(",",selected)+" FROM CONTRACT_CANDIDATE WHERE PROJECT_ID=?")){
            p.setString(1,project);p.setMaxRows(201);p.setQueryTimeout(20);
            try(var r=p.executeQuery()){while(r.next()){var row=new LinkedHashMap<String,Object>();row.put("candidateId",uuid(r.getString("ID")));for(String col:selected){Object value=r.getObject(col);row.put(col+"_PRESENT",value!=null&&!value.toString().isBlank());}result.add(row);}}
        }return result;
    }
    static void localInput(String id,String body,String storageKey,long size,boolean probe){
        if(inputs.size()>=80)return;var in=new LinkedHashMap<String,Object>();in.put("fileId",uuid(id));
        if(storageKey.matches("[a-f0-9]{64}"))in.put("storageKey",storageKey);in.put("size",size);
        if(probe&&!body.isBlank()){
            var segments=new ArrayList<Map<String,String>>();
            var m=SIGNALS.get("BUDGET").matcher(body);int pos=0;boolean first=true,nearMoney=false;
            while(m.find()){if(first){pos=m.start();first=false;}if(MONEY.matcher(body.substring(m.start(),Math.min(body.length(),m.end()+160))).find()){pos=m.start();nearMoney=true;break;}}
            in.put("budgetMoneyHint",nearMoney);int start=Math.max(0,pos-120),end=Math.min(body.length(),start+1700);
            segments.add(Map.of("locator","diagnostic/"+start,"text",body.substring(start,end)));
            if(start>1700)segments.add(Map.of("locator","diagnostic/head","text",body.substring(0,Math.min(body.length(),900))));
            in.put("segments",segments);
        }inputs.add(in);
    }
    static Map<String,Object> procurement(Connection c,JsonNode snapshot,boolean probe)throws Exception {
        String id=text(snapshot,"id");var result=new LinkedHashMap<String,Object>();result.put("caseId",uuid(id));result.put("status",code(text(snapshot,"status")));
        try{result.put("createdAt",Instant.parse(text(snapshot,"createdAt")).toString());}catch(Exception ignored){result.put("createdAt",null);}
        var report=snapshot.path("report");result.put("hasReport",report.isObject());result.put("budgetPresent",report.hasNonNull("budget"));result.put("awardPresent",report.hasNonNull("awardAmount"));result.put("offers",report.path("offers").size());
        result.put("quoteStatus",code(text(report,"quoteStatus")));result.put("ceilingPresent",report.hasNonNull("ceilingAmount"));
        var modules=new LinkedHashMap<String,Object>();for(String module:List.of("AGENT_PRECHECK","TENDER_COMPLIANCE")){var review=report.path("reviews").path(module);if(review.isObject()){
            int supplied=0,source=0;for(var scope:review.path("scope")){source+=scope.path("sourceChars").asInt();supplied+=scope.path("suppliedChars").asInt();}
            var details=new LinkedHashMap<String,Object>();details.put("status",code(text(review,"status")));details.put("errorCode",code(text(review,"errorCode")));details.put("rejectedCitations",review.path("rejectedCitations").asInt());details.put("checks",review.path("checks").size());details.put("findings",review.path("findings").size());details.put("scopeFiles",review.path("scope").size());details.put("sourceChars",source);details.put("suppliedChars",supplied);details.put("coverageGapCount",review.path("coverageGaps").size());modules.put(module,details);
        }}result.put("modules",modules);
        var documents=new ArrayList<Map<String,Object>>();
        for(var stored:snapshots(c,"AUDIT_CASE_FILE","CASE_ID=? ORDER BY FILE_ORDER",List.of(id),201)){
            var d=stored.path("document");if(!d.isObject())d=stored;String fid=text(d,"id");StringBuilder body=new StringBuilder();for(var segment:d.path("segments"))body.append(text(segment,"text")).append('\n');
            var doc=new LinkedHashMap<String,Object>();doc.put("fileId",uuid(fid));doc.put("status",code(text(d,"status")));doc.put("parseMethod",code(text(d,"parseMethod")));doc.put("errorCode",code(text(d,"errorCode")));doc.put("bytes",d.path("size").asLong());doc.put("calls",d.path("calls").asInt());doc.put("segments",d.path("segments").size());doc.put("text",features(body.toString()));doc.put("nameHints",signals(text(d,"name")));doc.put("persistedFactCount",stored.path("factCount").asInt());
            var facts=new ArrayList<JsonNode>();try(var p=c.prepareStatement("SELECT FACT_JSON FROM AUDIT_FACT WHERE CASE_ID=? AND FILE_ID=? ORDER BY FACT_ORDER")){p.setString(1,id);p.setString(2,fid);p.setMaxRows(5000);p.setQueryTimeout(20);try(var r=p.executeQuery()){while(r.next())facts.add(JSON.readTree(lob(r,"FACT_JSON")));}}
            doc.put("factTypes",counts(JSON.valueToTree(facts),"type"));doc.put("sourceVerifiedFacts",facts.stream().filter(f->text(f,"verification").equals("SOURCE_VERIFIED")).count());
            doc.put("pageCoverage",pageCoverage(d.path("segments")));doc.put("fileOrder",documents.size()+1);
            var ledgers=new ArrayList<Map<String,Object>>();
            for(int attempt=0;attempt<2;attempt++) {
                String material=attempt==0?fid:UUID.nameUUIDFromBytes((fid+"/missing-fields-v1").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                String ledgerId=UUID.nameUUIDFromBytes(("material-extraction/PROCUREMENT_AUTO/"+material).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                ledgers.add(Map.of("attempt",attempt+1,"ledgerId",ledgerId,"rows",metadata(c,"MATERIAL_EXTRACTION_LEDGER","ID STATUS UNIT_COUNT NEXT_UNIT CALL_COUNT USED_MILLIS REASON_CODE UPDATED_AT DEADLINE_AT","ID=?",List.of(ledgerId))));
            }
            doc.put("extractionLedgers",ledgers);
            int before=inputs.size();localInput(fid,body.toString(),text(d,"storageKey"),d.path("size").asLong(),probe);
            if(inputs.size()>before){inputs.get(before).put("caseId",uuid(id));inputs.get(before).put("fileOrder",documents.size()+1);}
            documents.add(doc);
        }result.put("documents",documents);result.put("fileLimitReached",documents.size()>200);return result;
    }
    static Map<String,Object> contract(Connection c,JsonNode snapshot,boolean probe)throws Exception {
        String project=text(snapshot,"projectId");var result=new LinkedHashMap<String,Object>();result.put("caseId",uuid(text(snapshot,"id")));result.put("projectId",uuid(project));result.put("status",code(text(snapshot,"status")));result.put("reasonCode",code(text(snapshot,"reasonCode")));result.put("expectedUploads",snapshot.path("expectedUploads").asInt());
        var steps=new ArrayList<Map<String,Object>>();for(var step:snapshot.path("steps")){
            var output=step.path("output");var row=new LinkedHashMap<String,Object>();row.put("fileId",uuid(text(step,"fileId")));row.put("kind",code(text(step,"kind")));row.put("status",code(text(step,"status")));row.put("reasonCode",code(text(step,"reasonCode")));row.put("start",step.path("start").asInt());row.put("end",step.path("end").asInt());row.put("complete",output.path("complete").asBoolean());row.put("rejected",output.path("rejectedCitations").asInt());row.put("factTypes",counts(output.path("facts"),"type"));row.put("role",code(text(output,"role")));row.put("profile",code(text(output,"profile")));row.put("clauses",output.path("clauses").size());row.put("findings",output.path("findings").size());steps.add(row);
        }result.put("steps",steps);
        var documents=new ArrayList<Map<String,Object>>();
        for(var s:snapshot.path("sources")){
            String fid=text(s,"fileId"),parse=text(s,"parseResultId"),body="";var doc=new LinkedHashMap<String,Object>();doc.put("fileId",uuid(fid));doc.put("status",code(text(s,"status")));doc.put("reasonCode",code(text(s,"reasonCode")));doc.put("nameHints",signals(text(s,"name")));doc.put("bytes",s.path("size").asLong());
            if(!parse.isEmpty())try(var p=c.prepareStatement("SELECT CANONICAL_TEXT FROM PROJECT_FILE_PARSE_RESULT WHERE PARSE_RESULT_ID=? AND FILE_ID=?")){p.setString(1,parse);p.setString(2,fid);p.setQueryTimeout(20);try(var r=p.executeQuery()){if(r.next())body=lob(r,"CANONICAL_TEXT");}}
            doc.put("text",features(body));doc.put("parse",metadata(c,"PROJECT_FILE_PARSE_RESULT","PARSE_RESULT_ID PARSE_VERSION PARSE_STATUS PARSE_METHOD PAGE_COUNT DURATION_MS CONFIDENCE CREATED_AT COMPLETED_AT","FILE_ID=?",List.of(fid)));
            doc.put("jobs",metadata(c,"PROJECT_FILE_PROCESSING_JOB","JOB_ID STATUS ATTEMPT_NO REASON_CODE CREATED_AT UPDATED_AT","FILE_ID=?",List.of(fid)));
            doc.put("roles",metadata(c,"PROJECT_FILE_ROLE_DECISION","DECISION_ID SELECTED_ROLE DECISION_STATUS PARSE_RESULT_ID VERSION_NO","FILE_ID=?",List.of(fid)));
            doc.put("identity",metadata(c,"PROJECT_FILE_IDENTITY_DECISION","DECISION_ID RESOLUTION DECISION_STATUS VERSION_NO","FILE_ID=?",List.of(fid)));
            String storage="";if(columns(c,"PROJECT_FILE").contains("STORAGE_KEY"))try(var p=c.prepareStatement("SELECT STORAGE_KEY FROM PROJECT_FILE WHERE ID=?")){p.setString(1,fid);p.setQueryTimeout(15);try(var r=p.executeQuery()){if(r.next())storage=r.getString(1);}}
            localInput(fid,body,storage==null?"":storage,s.path("size").asLong(),false);documents.add(doc);
        }result.put("documents",documents);
        result.put("intakeFiles",metadata(c,"PROJECT_FILE","ID CATEGORY INGESTION_STATUS PARSE_STATUS PARSE_METHOD PARSE_CONFIDENCE PAGE_COUNT PARSE_DURATION_MS SAFE_REASON_CODE SIZE_BYTES CREATED_AT UPDATED_AT","PROJECT_ID=?",List.of(project)));
        result.put("candidates",metadata(c,"CONTRACT_CANDIDATE","ID SOURCE_CONTRACT_FILE_ID SOURCE_PARSE_RESULT_ID SOURCE_ROLE_DECISION_ID SOURCE_IDENTITY_DECISION_ID PROMOTION_STATE PROMOTED_CONTRACT_ID VERSION_NO","PROJECT_ID=?",List.of(project)));
        result.put("candidateFieldPresence",candidatePresence(c,project));
        var runs=metadata(c,"CONTRACT_REVIEW_RUN","RUN_ID CONTRACT_ID STATUS SAFE_REASON_CODE CREATED_AT REQUESTED_AT COMPLETED_AT VERSION_NO","PROJECT_ID=?",List.of(project));result.put("formalRuns",runs);
        var formalDetails=new ArrayList<Map<String,Object>>();for(var run:runs.stream().limit(10).toList()){
            String runId=Objects.toString(run.get("RUN_ID"),"");if(uuid(runId).equals("UNAVAILABLE"))continue;
            formalDetails.add(Map.of("runId",runId,"steps",metadata(c,"CONTRACT_REVIEW_STEP","STEP_ID STEP_TYPE STATUS CURRENT_ATTEMPT_NO SAFE_REASON_CODE STARTED_AT COMPLETED_AT","RUN_ID=?",List.of(runId)),"reports",metadata(c,"CONTRACT_REVIEW_REPORT","REPORT_ID STATUS REPORT_VERSION SAFE_REASON_CODE REQUESTED_AT GENERATED_AT","RUN_ID=?",List.of(runId))));
        }result.put("formalRunDetails",formalDetails);result.put("formalRunDetailLimitReached",runs.size()>10);
        result.put("projectContracts",metadata(c,"PROJECT_CONTRACT","CONTRACT_ID CANDIDATE_ID","PROJECT_ID=?",List.of(project)));
        return result;
    }
    public static void main(String[] args)throws Exception {
        Path vocabularyPath=Path.of(FieldDiag.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("vocabulary.json");
        VOCABULARY.addAll(JSON.readValue(Files.readString(vocabularyPath),JSON.getTypeFactory().constructCollectionType(List.class,String.class)));
        if(args.length==1&&args[0].equals("--self-test")){
            var x=features("保密单位：秘密。\n本项目的预算为2200000人民币。\n合同金额为壹拾贰万元整。");String exported=JSON.writeValueAsString(x);
            if(exported.contains("2200000")||exported.contains("保密")||exported.contains("秘密")||!exported.contains("BUDGET"))throw new AssertionError();
            if(!MONEY.matcher("2200000人民币").find()||!MONEY.matcher("壹拾贰万元整").find()||!code("SECRET_CANARY_UPPERCASE").equals("OTHER_OR_UNAVAILABLE"))throw new AssertionError();
            var coverage=pageCoverage(JSON.readTree("[{\"locator\":\"pdf/page:162/ocr/part:1\",\"text\":\"保密金额2200000\"},{\"locator\":\"保密文件名\",\"text\":\"隐私\"}]"));
            String projected=JSON.writeValueAsString(coverage);
            if(!coverage.get("lastPage").equals(162)||!coverage.get("unmappedSegments").equals(1)||projected.contains("保密")||projected.contains("2200000"))throw new AssertionError();
            System.out.println("FIELD_FEATURE_PRIVACY_SELFTEST=PASS");return;
        }
        LocalDate day=LocalDate.parse(args[0]);int limit=Integer.parseInt(args[1]);boolean probe=Boolean.parseBoolean(args[3]);
        var out=new LinkedHashMap<String,Object>();out.put("format","scan-diag-v1");out.put("readOnly",true);out.put("day",day.toString());var cases=new LinkedHashMap<String,Object>();
        try{
            DriverManager.setLoginTimeout(15);try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))){
                c.setReadOnly(true);c.setAutoCommit(false);
                for(String table:List.of("AUDIT_CASE")){
                    if(columns(c,table).isEmpty()){cases.put(table,Map.of("status","TABLE_NOT_PRESENT_OR_VISIBLE"));continue;}
                    var rows=new ArrayList<Map<String,Object>>();
                    try(var p=c.prepareStatement("SELECT SNAPSHOT_JSON FROM "+table+" WHERE CREATED_AT>=? AND CREATED_AT<? ORDER BY CREATED_AT DESC")){
                        // Broad UTC/local overlap; fixed cap and exported clocks make scope explicit.
                        p.setTimestamp(1,Timestamp.valueOf(day.minusDays(1).atStartOfDay()));p.setTimestamp(2,Timestamp.valueOf(day.plusDays(2).atStartOfDay()));p.setMaxRows(limit);p.setQueryTimeout(30);
                        try(var r=p.executeQuery()){while(r.next())try{var snapshot=JSON.readTree(lob(r,"SNAPSHOT_JSON"));rows.add(table.equals("AUDIT_CASE")?procurement(c,snapshot,probe):contract(c,snapshot,probe));}catch(Exception e){rows.add(Map.of("status","CASE_INSPECTION_FAILED","errorType",e.getClass().getSimpleName().replaceAll("[^A-Za-z]","")));}}
                    }cases.put(table,Map.of("status","OK","rows",rows,"limitReached",rows.size()==limit));
                }c.rollback();out.put("connection","OK");
            }
        }catch(SQLException e){out.put("connection",Map.of("status","DATABASE_ERROR","dbCode",e.getErrorCode()));}
        out.put("cases",cases);out.put("contentExported",false);
        Path privatePath=Path.of(args[2]);Files.writeString(privatePath,JSON.writeValueAsString(inputs));
        System.out.println(JSON.writeValueAsString(out));
    }
}
