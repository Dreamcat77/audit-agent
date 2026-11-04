import java.sql.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Standalone offline executable; never opens a database or reads credentials. */
public class SemanticCheckpointMaintenanceTest {
    interface Check {void run()throws Exception;}
    static int tests;
    static Path root,runtime;static SemanticCheckpointCatalog.Plan plan;
    static void check(boolean value){if(!value)throw new AssertionError("EXPECTED_SAFETY_BEHAVIOR_ABSENT");}
    static void reject(Check body)throws Exception{try{body.run();}catch(IllegalStateException expected){return;}throw new AssertionError("UNSAFE_OPERATION_ACCEPTED");}
    static void test(String name,Check body)throws Exception{body.run();tests++;System.out.println("PASS "+name);}
    static Word97Maintenance.Fact fact(String kind,String... values){return new Word97Maintenance.Fact(kind,Collections.unmodifiableList(Arrays.asList(values)));}
    static void index(List<Word97Maintenance.Fact> facts,String table,String name,String unique,String type,String generated,List<String> columns){
        facts.add(fact("INDEX",table,name,unique,"VALID",type));facts.add(fact("INDEX_GENERATION",table,name,generated));
        int position=0;for(String col:columns)facts.add(fact("INDEX_COLUMN",table,name,col,Integer.toString(++position),"ASC"));
    }
    static List<Word97Maintenance.Fact> facts(int stage){
        List<Word97Maintenance.Fact> facts=new ArrayList<>();
        for(String table:List.of("CONTRACT_REVIEW_RUN","CONTRACT_REVIEW_STEP","CONTRACT_REVIEW_STEP_ATTEMPT")){
            String key=table.equals("CONTRACT_REVIEW_RUN")?"RUN_ID":table.equals("CONTRACT_REVIEW_STEP")?"STEP_ID":"ATTEMPT_ID";
            facts.add(fact("TABLE",table));facts.add(fact("CONSTRAINT",table,"PK_"+table,"P",null,null,null,"ENABLED",null));facts.add(fact("CONSTRAINT_COLUMN",table,"PK_"+table,key,"1"));
        }
        for(int i=0;i<Math.min(stage,2);i++){
            String table=SemanticCheckpointCatalog.TABLES.get(i);facts.add(fact("TABLE",table));int ordinal=0;
            for(var column:plan.columns().get(table)){
                String type=column.type(),width=type.startsWith("VARCHAR")?type.substring(8,type.length()-1):type.equals("BIGINT")?"8":"4";
                facts.add(fact("COLUMN",table,column.name(),type.startsWith("VARCHAR")?"VARCHAR":type,width,null,null,column.nullable()?"Y":"N",column.zeroDefault()?"0":null,Integer.toString(++ordinal)));
            }
            for(var constraint:plan.constraints().get(table).entrySet()){
                String value=constraint.getValue(),kind=value.startsWith("PRIMARY")?"P":value.startsWith("UNIQUE")?"U":value.startsWith("FOREIGN")?"R":"C";
                String owner=null,parentKey=null,delete=null;
                if(kind.equals("R")){Matcher ref=Pattern.compile(".*REFERENCES ([A-Z0-9_]+)\\(([^)]+)\\)",Pattern.DOTALL).matcher(value);check(ref.matches());owner="FIXTURE_SCHEMA";parentKey=ref.group(1).equals("CONTRACT_SEMANTIC_PLAN")?"PK_CONTRACT_SEMANTIC_PLAN":"PK_"+ref.group(1);delete="NO ACTION";}
                facts.add(fact("CONSTRAINT",table,constraint.getKey(),kind,owner,parentKey,delete,"ENABLED",kind.equals("C")?value.substring(5):null));
                if(!kind.equals("C")){int pos=0;for(String col:ContractLocalMigration.columns(value.substring(value.indexOf('(')+1,value.indexOf(')'))))facts.add(fact("CONSTRAINT_COLUMN",table,constraint.getKey(),col,Integer.toString(++pos)));}
            }
            if(i==0){
                // Exact PLAN index shape observed in read-only DM8 receipts 02/03.
                index(facts,table,"INDEX33556132","NONUNIQUE","CLUSTER","Y",List.of());
                index(facts,table,"INDEX33556133","UNIQUE","NORMAL","Y",List.of("PLAN_ID"));
                index(facts,table,"INDEX33556134","UNIQUE","NORMAL","Y",List.of("STEP_ID"));
                index(facts,table,"INDEX33556135","NONUNIQUE","VIRTUAL","Y",List.of("RUN_ID"));
                index(facts,table,"INDEX33556136","NONUNIQUE","VIRTUAL","Y",List.of("STEP_ID"));
                index(facts,table,"INDEX33556137","NONUNIQUE","VIRTUAL","Y",List.of("ORIGINAL_ATTEMPT_ID"));
                index(facts,table,"INDEX33556138","NONUNIQUE","VIRTUAL","Y",List.of("CURRENT_ATTEMPT_ID"));
            }else{
                index(facts,table,"UNIT_CLUSTER","NONUNIQUE","CLUSTER","Y",List.of());
                index(facts,table,"UNIT_PK","UNIQUE","NORMAL","Y",List.of("UNIT_EXECUTION_ID"));
                index(facts,table,"UNIT_EXEC","UNIQUE","NORMAL","Y",List.of("PLAN_ID","LOGICAL_UNIT_KEY","EXECUTION_NO"));
                index(facts,table,"UNIT_CALL","UNIQUE","NORMAL","Y",List.of("CALL_AUDIT_ID"));
                index(facts,table,"UNIT_FK_PLAN","NONUNIQUE","VIRTUAL","Y",List.of("PLAN_ID"));
                index(facts,table,"UNIT_FK_ATTEMPT","NONUNIQUE","VIRTUAL","Y",List.of("ATTEMPT_ID"));
            }
        }
        if(stage==3)index(facts,"CONTRACT_SEMANTIC_UNIT",SemanticCheckpointCatalog.INDEX,"NONUNIQUE","NORMAL","N",List.of("PLAN_ID","UNIT_ORDINAL","EXECUTION_NO"));
        return facts;
    }
    static List<Word97Maintenance.Fact> changedIndex(int stage,String kind,String name,int position,String value){
        var changed=new ArrayList<>(facts(stage));var old=changed.stream().filter(f->f.kind().equals(kind)&&f.values().get(1).equals(name)).findFirst().orElseThrow();
        var row=new ArrayList<>(old.values());row.set(position,value);changed.set(changed.indexOf(old),new Word97Maintenance.Fact(kind,row));return changed;
    }
    static <T> T jdbcProxy(Class<T> type,java.lang.reflect.InvocationHandler body){
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},body));
    }
    static ResultSet catalogResult(List<List<String>> data){
        int[] row={-1};var metadata=jdbcProxy(ResultSetMetaData.class,(proxy,method,args)->{
            if(method.getName().equals("getColumnCount"))return data.isEmpty()?1:data.get(0).size();
            throw new UnsupportedOperationException("UNEXPECTED_SYNTHETIC_METADATA_CALL");
        });
        return jdbcProxy(ResultSet.class,(proxy,method,args)->switch(method.getName()){
            case "next"->++row[0]<data.size();case "getMetaData"->metadata;
            case "getString"->data.get(row[0]).get((Integer)args[0]-1);case "close"->null;
            default->throw new UnsupportedOperationException("UNEXPECTED_SYNTHETIC_RESULT_CALL");
        });
    }
    static List<Word97Maintenance.Fact> withCheck(String name,String expression){
        return withCheck(3,name,expression);
    }
    static List<Word97Maintenance.Fact> withCheck(int stage,String name,String expression){
        var changed=new ArrayList<>(facts(stage));var prior=changed.stream().filter(f->f.kind().equals("CONSTRAINT")&&f.values().get(1).equals(name)).findFirst().orElseThrow();
        var row=new ArrayList<>(prior.values());row.set(7,expression);changed.set(changed.indexOf(prior),new Word97Maintenance.Fact("CONSTRAINT",row));return changed;
    }
    static SemanticCheckpointMaintenance.View view(int stage)throws Exception{
        Map<String,ResetReviewTestData.Table> tables=new TreeMap<>();
        for(String table:List.of("SYS_USER","SYS_ROLE","SYS_USER_ROLE","AUTH_LOGIN_AUDIT","AUDIT_OPERATION_LOG","CONTRACT_REVIEW_RUN","CONTRACT_REVIEW_STEP","CONTRACT_REVIEW_STEP_ATTEMPT")){
            var columns=List.of(new ResetReviewTestData.Column("ID",Types.VARCHAR,"VARCHAR",false),new ResetReviewTestData.Column("BODY",Types.CLOB,"CLOB",true),new ResetReviewTestData.Column("BYTES",Types.BLOB,"BLOB",false),new ResetReviewTestData.Column("MONEY",Types.DECIMAL,"DECIMAL",false),new ResetReviewTestData.Column("STAMP",Types.TIMESTAMP,"TIMESTAMP",false));
            tables.put(table,new ResetReviewTestData.Table(table,columns,List.of("ID"),List.of(Arrays.asList("synthetic-"+table,"synthetic \uD840\uDC00",new byte[]{0,-1,5},new BigDecimal("100.20"),Timestamp.valueOf("2026-10-16 00:00:00.123456789")))));
        }
        for(int i=0;i<Math.min(stage,2);i++){String table=SemanticCheckpointCatalog.TABLES.get(i);tables.put(table,new ResetReviewTestData.Table(table,List.of(new ResetReviewTestData.Column("ID",Types.VARCHAR,"VARCHAR",false)),List.of("ID"),List.of()));}
        return new SemanticCheckpointMaintenance.View(tables,facts(stage),stage);
    }
    static Path directory()throws Exception{Path dir=Files.createTempDirectory(runtime,"case-");Files.writeString(dir.resolve("writers-stopped.properties"),"synthetic=true\n");return dir;}
    static Properties snapshot(Path dir)throws Exception{return SemanticCheckpointMaintenance.snapshot(dir,view(0),"identity","source","ddl");}
    static class FakeDb implements SemanticCheckpointMaintenance.Database {
        int stage,executions,failAfter=-1,failBefore=-1;SemanticCheckpointMaintenance.View replacement;
        public SemanticCheckpointMaintenance.View inspect()throws Exception{return replacement==null?view(stage):replacement;}
        public void execute(String ddl)throws Exception{check(ddl.equals(plan.statements().get(stage)));if(stage==failBefore){failBefore=-1;throw new SQLException("synthetic before commit");}stage++;executions++;if(stage==failAfter){failAfter=-1;throw new SQLException("synthetic after implicit commit");}}
    }
    static void apply(FakeDb db,Path dir,Properties p)throws Exception{SemanticCheckpointMaintenance.apply(db,dir,p,"identity","source","ddl",plan.statements());}
    public static void main(String[] args) throws Exception {
        root=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();
        if(args.length==2&&args[1].equals("--source")){System.out.print(SemanticCheckpointMaintenance.source(root));return;}
        System.out.println("OFFLINE_SYNTHETIC_DATABASE_ONLY REAL_DATABASE_CONNECTIONS=0");
        runtime=root.resolve(".runtime/contract-repair-test/closeout-20261016/p6-offline-fixtures");Files.createDirectories(runtime);plan=SemanticCheckpointCatalog.plan(root);
        if (!SemanticCheckpointCatalog.typeMatches("BIGINT","BIGINT",Types.BIGINT,"BIGINT"))
            throw new AssertionError("APPROVED_BIGINT_CATALOG_NOT_SUPPORTED");
        if (!SemanticCheckpointCatalog.typeMatches("CLOB","CLOB",Types.CLOB,"CLOB"))
            throw new AssertionError("APPROVED_CLOB_CATALOG_NOT_SUPPORTED");
        test("sole SQL has only two tables and one index",()->{check(plan.statements().size()==3);check(plan.columns().get("CONTRACT_SEMANTIC_PLAN").size()==26);reject(()->SemanticCheckpointCatalog.parse(Files.readString(root.resolve(SemanticCheckpointCatalog.DDL))+"\nDROP TABLE CONTRACT;"));});
        test("real DM8 generated ROWID unique-key and own-FK index shape proves the exact first prefix",()->{
            check(SemanticCheckpointCatalog.prefix(facts(1),plan,"FIXTURE_SCHEMA")==1);
            check(SemanticCheckpointCatalog.rows(facts(1),"INDEX","CONTRACT_SEMANTIC_PLAN").size()==7);
        });
        test("generated indexes reject missing duplicate flags types validity own columns and orphan metadata",()->{
            for(String name:List.of("INDEX33556132","INDEX33556133","INDEX33556135")){
                var entirelyMissing=new ArrayList<>(facts(1));entirelyMissing.removeIf(f->Set.of("INDEX","INDEX_GENERATION","INDEX_COLUMN").contains(f.kind())&&f.values().get(1).equals(name));reject(()->SemanticCheckpointCatalog.prefix(entirelyMissing,plan,"FIXTURE_SCHEMA"));
                for(String kind:List.of("INDEX","INDEX_GENERATION")){
                    var missing=new ArrayList<>(facts(1));missing.removeIf(f->f.kind().equals(kind)&&f.values().get(1).equals(name));reject(()->SemanticCheckpointCatalog.prefix(missing,plan,"FIXTURE_SCHEMA"));
                    var duplicate=new ArrayList<>(facts(1));duplicate.add(duplicate.stream().filter(f->f.kind().equals(kind)&&f.values().get(1).equals(name)).findFirst().orElseThrow());reject(()->SemanticCheckpointCatalog.prefix(duplicate,plan,"FIXTURE_SCHEMA"));
                }
                for(String flag:Arrays.asList("N","UNKNOWN",null))reject(()->SemanticCheckpointCatalog.prefix(changedIndex(1,"INDEX_GENERATION",name,2,flag),plan,"FIXTURE_SCHEMA"));
                reject(()->SemanticCheckpointCatalog.prefix(changedIndex(1,"INDEX",name,3,"UNUSABLE"),plan,"FIXTURE_SCHEMA"));
                reject(()->SemanticCheckpointCatalog.prefix(changedIndex(1,"INDEX",name,4,"UNKNOWN"),plan,"FIXTURE_SCHEMA"));
                reject(()->SemanticCheckpointCatalog.prefix(changedIndex(1,"INDEX",name,2,"WRONG"),plan,"FIXTURE_SCHEMA"));
            }
            for(String name:List.of("INDEX33556133","INDEX33556135")){
                for(var mutation:List.of(new String[]{"2","OTHER_COLUMN"},new String[]{"3","2"},new String[]{"4","DESC"}))
                    reject(()->SemanticCheckpointCatalog.prefix(changedIndex(1,"INDEX_COLUMN",name,Integer.parseInt(mutation[0]),mutation[1]),plan,"FIXTURE_SCHEMA"));
                var extra=new ArrayList<>(facts(1));extra.add(fact("INDEX_COLUMN","CONTRACT_SEMANTIC_PLAN",name,"OTHER_COLUMN","2","ASC"));reject(()->SemanticCheckpointCatalog.prefix(extra,plan,"FIXTURE_SCHEMA"));
            }
            var clusterColumns=new ArrayList<>(facts(1));clusterColumns.add(fact("INDEX_COLUMN","CONTRACT_SEMANTIC_PLAN","INDEX33556132","PLAN_ID","1","ASC"));reject(()->SemanticCheckpointCatalog.prefix(clusterColumns,plan,"FIXTURE_SCHEMA"));
            for(String type:List.of("CLUSTER","NORMAL","VIRTUAL")){
                var extra=new ArrayList<>(facts(1));index(extra,"CONTRACT_SEMANTIC_PLAN","EXTRA",type.equals("NORMAL")?"UNIQUE":"NONUNIQUE",type,"Y",type.equals("CLUSTER")?List.of():List.of("STEP_ID"));reject(()->SemanticCheckpointCatalog.prefix(extra,plan,"FIXTURE_SCHEMA"));
            }
            var orphan=new ArrayList<>(facts(1));orphan.add(fact("INDEX_GENERATION","CONTRACT_SEMANTIC_PLAN","ORPHAN","Y"));reject(()->SemanticCheckpointCatalog.prefix(orphan,plan,"FIXTURE_SCHEMA"));
            var orphanColumn=new ArrayList<>(facts(1));orphanColumn.add(fact("INDEX_COLUMN","CONTRACT_SEMANTIC_PLAN","ORPHAN","STEP_ID","1","ASC"));reject(()->SemanticCheckpointCatalog.prefix(orphanColumn,plan,"FIXTURE_SCHEMA"));
            for(var orphanFact:List.of(fact("INDEX_COLUMN","CONTRACT_SEMANTIC_UNIT","ORPHAN","PLAN_ID","1","ASC"),fact("INDEX_GENERATION","CONTRACT_SEMANTIC_UNIT","ORPHAN","Y"))){
                var absentTable=new ArrayList<>(facts(1));absentTable.add(orphanFact);reject(()->SemanticCheckpointCatalog.prefix(absentTable,plan,"FIXTURE_SCHEMA"));
            }
        });
        test("explicit IX remains exactly nongenerated normal and complete ordered columns",()->{
            for(var mutation:List.of(new String[]{"INDEX_GENERATION","2","Y"},new String[]{"INDEX","3","UNUSABLE"},new String[]{"INDEX","4","VIRTUAL"},new String[]{"INDEX_COLUMN","3","4"}))
                reject(()->SemanticCheckpointCatalog.prefix(changedIndex(3,mutation[0],SemanticCheckpointCatalog.INDEX,Integer.parseInt(mutation[1]),mutation[2]),plan,"FIXTURE_SCHEMA"));
            var missing=new ArrayList<>(facts(3));missing.removeIf(f->f.kind().equals("INDEX_GENERATION")&&f.values().get(1).equals(SemanticCheckpointCatalog.INDEX));reject(()->SemanticCheckpointCatalog.prefix(missing,plan,"FIXTURE_SCHEMA"));
            var duplicatePosition=new ArrayList<>(facts(3));var second=duplicatePosition.stream().filter(f->f.kind().equals("INDEX_COLUMN")&&f.values().get(1).equals(SemanticCheckpointCatalog.INDEX)&&f.values().get(3).equals("2")).findFirst().orElseThrow();
            var positionRow=new ArrayList<>(second.values());positionRow.set(3,"1");duplicatePosition.set(duplicatePosition.indexOf(second),new Word97Maintenance.Fact("INDEX_COLUMN",positionRow));
            reject(()->SemanticCheckpointCatalog.prefix(duplicatePosition,plan,"FIXTURE_SCHEMA"));
        });
        test("generation query is limited to the two new tables and cannot change protected old projection",()->{
            List<String> generationQueries=new ArrayList<>();
            List<List<String>> generationRows=new ArrayList<>(List.of(List.of("CONTRACT_SEMANTIC_PLAN","INDEX33556132","Y"),List.of("CONTRACT_SEMANTIC_UNIT","UNIT_CLUSTER","Y")));
            var connection=jdbcProxy(Connection.class,(proxy,method,jdbcArgs)->{
                if(!method.getName().equals("createStatement"))throw new UnsupportedOperationException("UNEXPECTED_SYNTHETIC_CONNECTION_CALL");
                return jdbcProxy(Statement.class,(p,m,a)->{
                    if(Set.of("setQueryTimeout","close").contains(m.getName()))return null;
                    if(!m.getName().equals("executeQuery"))throw new UnsupportedOperationException("UNEXPECTED_SYNTHETIC_STATEMENT_CALL");
                    String sql=(String)a[0];List<List<String>> data=List.of();
                    if(sql.equals("SELECT TABLE_NAME FROM USER_TABLES ORDER BY TABLE_NAME"))data=List.of(List.of("SYS_USER"));
                    if(sql.equals("SELECT TABLE_NAME,INDEX_NAME,UNIQUENESS,STATUS,INDEX_TYPE FROM USER_INDEXES ORDER BY TABLE_NAME,INDEX_NAME"))
                        data=List.of(List.of("SYS_USER","BASE_PK","UNIQUE","VALID","NORMAL"),List.of("CONTRACT_SEMANTIC_PLAN","INDEX33556132","NONUNIQUE","VALID","CLUSTER"));
                    if(sql.startsWith("SELECT TABLE_NAME,INDEX_NAME,GENERATED ")){
                        generationQueries.add(sql);
                        data=generationRows;
                    }
                    return catalogResult(data);
                });
            });
            var observed=SemanticCheckpointCatalog.facts(connection);
            check(generationQueries.equals(List.of("SELECT TABLE_NAME,INDEX_NAME,GENERATED FROM USER_INDEXES WHERE TABLE_NAME IN ('CONTRACT_SEMANTIC_PLAN','CONTRACT_SEMANTIC_UNIT') ORDER BY TABLE_NAME,INDEX_NAME")));
            check(observed.stream().filter(f->f.kind().equals("INDEX_GENERATION")).count()==2);
            var legacy=List.of(fact("TABLE","SYS_USER"),fact("INDEX","SYS_USER","BASE_PK","UNIQUE","VALID","NORMAL"));
            check(SemanticCheckpointMaintenance.projection(new SemanticCheckpointMaintenance.View(Map.of(),observed,0))
                .equals(SemanticCheckpointMaintenance.projection(new SemanticCheckpointMaintenance.View(Map.of(),legacy,0))));
            generationRows.add(List.of("SYS_USER","BASE_PK","Y"));reject(()->SemanticCheckpointCatalog.facts(connection));
        });
        test("each of four exact catalog prefixes is recognized",()->{for(int stage=0;stage<=3;stage++)check(SemanticCheckpointCatalog.prefix(facts(stage),plan,"FIXTURE_SCHEMA")==stage);});
        test("real DM8 not-equal and NOT IS NULL display retain approved CHECK semantics at every prefix",()->{
            // Exact read-only USER_CONSTRAINTS display captured after first CREATE.
            String actual=" (STATUS != 'COMPLETE' OR  NOT (FINAL_RESULT_SHA256 IS NULL ) )";
            for(int stage=1;stage<=3;stage++)check(SemanticCheckpointCatalog.prefix(withCheck(stage,"CK_SEM_PLAN_FINAL",actual),plan,"FIXTURE_SCHEMA")==stage);
            check(SemanticCheckpointCatalog.prefix(withCheck("CK_SEM_UNIT_RESULT","STATUS != 'VERIFIED' OR (NOT (RESULT_JSON IS NULL) AND NOT (RESULT_SHA256 IS NULL))"),plan,"FIXTURE_SCHEMA")==3);
            check(SemanticCheckpointCatalog.prefix(withCheck("CK_SEM_UNIT_CALL","STATUS IN ('PLANNED','CLAIMED') OR (NOT (CALL_AUDIT_ID IS NULL) AND NOT (PROVIDER IS NULL) AND NOT (MODEL_NAME IS NULL) AND NOT (REQUEST_FINGERPRINT IS NULL) AND CALL_ORDINAL > 0)"),plan,"FIXTURE_SCHEMA")==3);
        });
        test("NOT display cannot change nullable logic literal data identity or boolean grouping",()->{
            for(String altered:List.of(
                "STATUS != 'complete' OR NOT (FINAL_RESULT_SHA256 IS NULL)",
                "STATUS != 'COM PLETE' OR NOT (FINAL_RESULT_SHA256 IS NULL)",
                "STATUS != 'COM''PLETE' OR NOT (FINAL_RESULT_SHA256 IS NULL)",
                "STATUS != 'COMPLETE' OR (FINAL_RESULT_SHA256 IS NULL)",
                "STATUS != 'COMPLETE' OR NOT (FINAL_RESULT_SHA256 IS NOT NULL)",
                "STATUS != 'COMPLETE' AND NOT (FINAL_RESULT_SHA256 IS NULL)",
                "STATUS != 'COMPLETE' OR NOT (FINAL_RESULT_SHA256 IS NULL OR STATUS = 'COMPLETE')",
                "NOT (STATUS != 'COMPLETE' OR FINAL_RESULT_SHA256 IS NULL)",
                "STATUS != 'COMPLETE' OR NOT (FINAL_RESULT_SHA256 IS NULL) OR 1=1",
                "STATUS != 'COMPLETE' OR NOT (\"final_result_sha256\" IS NULL)",
                "STATUS != 'COMPLETE' OR NOT (FINAL_RESULT_SHA256 IS NULL); UNKNOWN")){
                reject(()->SemanticCheckpointCatalog.prefix(withCheck("CK_SEM_PLAN_FINAL",altered),plan,"FIXTURE_SCHEMA"));
            }
            reject(()->SemanticCheckpointCatalog.prefix(withCheck("CK_SEM_UNIT_RESULT","(STATUS != 'VERIFIED' OR NOT (RESULT_JSON IS NULL)) AND NOT (RESULT_SHA256 IS NULL)"),plan,"FIXTURE_SCHEMA"));
            reject(()->SemanticCheckpointCatalog.prefix(withCheck("CK_SEM_UNIT_RESULT","STATUS != 'VERIFIED' OR NOT (RESULT_JSON IS NULL AND RESULT_SHA256 IS NULL)"),plan,"FIXTURE_SCHEMA"));
        });
        test("CHECK literal case whitespace quotes and boolean grouping cannot be normalized away",()->{
            String[][] mutations={
                {"CK_SEM_PLAN_STATUS","STATUS IN ('active','COMPLETE')"},
                {"CK_SEM_UNIT_PURPOSE","PURPOSE IN ('ANALYZE','RE PAIR','SYNTHESIZE')"},
                {"CK_SEM_UNIT_PURPOSE","PURPOSE IN ('ANALYZE','RE\"PAIR','SYNTHESIZE')"},
                {"CK_SEM_UNIT_PURPOSE","PURPOSE IN ('ANALYZE','RE''PAIR','SYNTHESIZE')"},
                {"CK_SEM_PLAN_STATUS","STATUS IN (\"ACTIVE\",'COMPLETE')"},
                {"CK_SEM_PLAN_STATUS","\"status\" IN ('ACTIVE','COMPLETE')"},
                {"CK_SEM_PLAN_FINAL","STATUS <> 'COMPLETE' AND FINAL_RESULT_SHA256 IS NOT NULL"},
                {"CK_SEM_UNIT_RESULT","(STATUS <> 'VERIFIED' OR RESULT_JSON IS NOT NULL) AND RESULT_SHA256 IS NOT NULL"},
                {"CK_SEM_PLAN_FINAL","STATUS <> 'COMPLETE' OR FINAL_RESULT_SHA256 IS NOT NULL -- unproven"}
            };
            int accepted=0;
            for(int i=0;i<mutations.length;i++){
                try{SemanticCheckpointCatalog.prefix(withCheck(mutations[i][0],mutations[i][1]),plan,"FIXTURE_SCHEMA");accepted++;System.out.println("FAIL CHECK_MUTATION_ACCEPTED="+(i+1));}
                catch(IllegalStateException expected){}
            }
            check(accepted==0);
        });
        test("CHECK display whitespace identifier quotes and redundant parentheses retain meaning",()->{
            check(SemanticCheckpointCatalog.prefix(withCheck("CK_SEM_PLAN_STATUS"," (( \"STATUS\" in ( 'ACTIVE' , 'COMPLETE' ) )) "),plan,"FIXTURE_SCHEMA")==3);
            check(SemanticCheckpointCatalog.prefix(withCheck("CK_SEM_PLAN_FINAL","((\"STATUS\" != 'COMPLETE')) or ((\"FINAL_RESULT_SHA256\" is not null))"),plan,"FIXTURE_SCHEMA")==3);
            check(SemanticCheckpointCatalog.prefix(withCheck("CK_SEM_UNIT_RESULT","((STATUS <> 'VERIFIED') OR ((RESULT_JSON IS NOT NULL) AND (RESULT_SHA256 IS NOT NULL)))"),plan,"FIXTURE_SCHEMA")==3);
        });
        test("missing table or wrong CHECK FK column or index cannot pass",()->{
            for(String kind:List.of("COLUMN","CONSTRAINT","INDEX_COLUMN")){
                var altered=new ArrayList<>(facts(3));var target=altered.stream().filter(f->f.kind().equals(kind)&&SemanticCheckpointCatalog.TABLES.contains(f.values().get(0))).findFirst().orElseThrow();altered.remove(target);reject(()->SemanticCheckpointCatalog.prefix(altered,plan,"FIXTURE_SCHEMA"));
            }
            var trigger=new ArrayList<>(facts(3));trigger.add(fact("TRIGGER","CONTRACT_SEMANTIC_PLAN","UNAPPROVED"));reject(()->SemanticCheckpointCatalog.prefix(trigger,plan,"FIXTURE_SCHEMA"));
            reject(()->SemanticCheckpointCatalog.prefix(facts(3),plan,"OTHER_SCHEMA"));
            var disabled=new ArrayList<>(facts(3));var prior=disabled.stream().filter(f->f.kind().equals("CONSTRAINT")&&f.values().get(1).equals("CK_SEM_PLAN_BUDGET")).findFirst().orElseThrow();var values=new ArrayList<>(prior.values());values.set(6,"DISABLED");disabled.set(disabled.indexOf(prior),new Word97Maintenance.Fact("CONSTRAINT",values));reject(()->SemanticCheckpointCatalog.prefix(disabled,plan,"FIXTURE_SCHEMA"));
            for(int position:List.of(3,6,7)){
                var changed=new ArrayList<>(facts(3));var col=changed.stream().filter(f->f.kind().equals("COLUMN")&&f.values().get(1).equals("CALL_LIMIT")).findFirst().orElseThrow();var row=new ArrayList<>(col.values());row.set(position,position==3?"8":position==6?"Y":"100");changed.set(changed.indexOf(col),new Word97Maintenance.Fact("COLUMN",row));reject(()->SemanticCheckpointCatalog.prefix(changed,plan,"FIXTURE_SCHEMA"));
            }
            var weakened=new ArrayList<>(facts(3));prior=weakened.stream().filter(f->f.kind().equals("CONSTRAINT")&&f.values().get(1).equals("CK_SEM_PLAN_BUDGET")).findFirst().orElseThrow();values=new ArrayList<>(prior.values());values.set(7,"TIME_LIMIT_MS > 0");weakened.set(weakened.indexOf(prior),new Word97Maintenance.Fact("CONSTRAINT",values));reject(()->SemanticCheckpointCatalog.prefix(weakened,plan,"FIXTURE_SCHEMA"));
        });
        test("complete typed snapshot reopens protected rows exact decimals blobs and nanos",()->{Path dir=directory();var p=snapshot(dir);SemanticCheckpointMaintenance.artifacts(dir,p,"identity","source","ddl");var table=ResetReviewTestData.readArchive(dir.resolve("SYS_USER.backup"));check(table.rows.get(0).get(3).equals(new BigDecimal("100.20")));check(((Timestamp)table.rows.get(0).get(4)).getNanos()==123456789);check(Arrays.equals((byte[])table.rows.get(0).get(2),new byte[]{0,-1,5}));});
        test("fixed predecessor reader revision preserves original snapshot and stop proof",()->{
            Path dir=directory();String old="4dea4c0f977acd9ad08a74ccc892ab622a31fb5a033e111d616d015bfc211ba3";
            String ddl="c377f0da3de48e9f57439081f9fade7126b7e7776e54026e4e8c842208d363dd",reader="a".repeat(64);
            Files.writeString(dir.resolve("writers-stopped.properties"),"format=SEMANTIC_WRITERS_STOPPED_V1\nsource.sha256="+old+"\nhead=f69be8eae55d1b54185de8805d92478e1d1c64d6\n");
            var p=SemanticCheckpointMaintenance.snapshot(dir,view(0),"identity",old,ddl);
            String snapshotHash=ResetReviewTestData.sha(dir.resolve("snapshot.properties")),stopHash=ResetReviewTestData.sha(dir.resolve("writers-stopped.properties"));
            Word97Maintenance.append(dir,snapshotHash,SemanticCheckpointMaintenance.progress(dir),0,1,"INTENT");
            reject(()->SemanticCheckpointMaintenance.issueReaderRevision(dir,p,"identity",reader,"b".repeat(40),ddl,view(1),false));
            reject(()->SemanticCheckpointMaintenance.issueReaderRevision(dir,p,"identity",reader,"b".repeat(40),ddl,view(0),true));
            SemanticCheckpointMaintenance.issueReaderRevision(dir,p,"identity",reader,"b".repeat(40),ddl,view(1),true);
            var revision=Word97Maintenance.read(dir.resolve("catalog-reader-revision.properties"));
            SemanticCheckpointMaintenance.artifacts(dir,p,"identity",reader,ddl);
            check(snapshotHash.equals(ResetReviewTestData.sha(dir.resolve("snapshot.properties")))&&stopHash.equals(ResetReviewTestData.sha(dir.resolve("writers-stopped.properties"))));
            String originalJournal=ResetReviewTestData.sha(dir.resolve("journal.log"));
            reject(()->SemanticCheckpointMaintenance.reconcile(dir,p,view(0)));
            check(originalJournal.equals(ResetReviewTestData.sha(dir.resolve("journal.log"))));
            var regressed=new FakeDb();reject(()->SemanticCheckpointMaintenance.apply(regressed,dir,p,"identity",reader,ddl,plan.statements()));check(regressed.executions==0);
            reject(()->SemanticCheckpointMaintenance.reconcile(dir,p,view(1)));
            check(SemanticCheckpointMaintenance.reconcile(dir,p,view(1))==1);
            SemanticCheckpointMaintenance.artifacts(dir,p,"identity",reader,ddl);
            var resumed=new FakeDb();resumed.stage=1;
            SemanticCheckpointMaintenance.apply(resumed,dir,p,"identity",reader,ddl,plan.statements());check(resumed.stage==3&&resumed.executions==2);
            reject(()->SemanticCheckpointMaintenance.artifacts(dir,p,"identity",old,ddl));
            reject(()->SemanticCheckpointMaintenance.artifacts(dir,p,"identity","c".repeat(64),ddl));
            revision.setProperty("snapshot.sha256","0".repeat(64));Word97Maintenance.storeNew(dir.resolve("corrupt-revision.properties"),revision);
            Files.move(dir.resolve("corrupt-revision.properties"),dir.resolve("catalog-reader-revision.properties"),StandardCopyOption.REPLACE_EXISTING);
            reject(()->SemanticCheckpointMaintenance.artifacts(dir,p,"identity",reader,ddl));
        });
        test("incoherent not-null metadata cannot authorize a backup",()->{
            var original=view(0);var tables=new TreeMap<>(original.tables());var user=tables.get("SYS_USER");var row=new ArrayList<>(user.rows.get(0));row.set(2,null);
            tables.put("SYS_USER",new ResetReviewTestData.Table("SYS_USER",user.columns,user.pk,List.of(row)));var invalid=new SemanticCheckpointMaintenance.View(tables,original.facts(),0);
            Path dir=directory();reject(()->SemanticCheckpointMaintenance.snapshot(dir,invalid,"identity","source","ddl"));
        });
        test("DDL source identity backup and journal tampering fail closed",()->{
            Path dir=directory();var p=snapshot(dir);reject(()->SemanticCheckpointMaintenance.artifacts(dir,p,"identity","source","changed-ddl"));Files.writeString(dir.resolve("SYS_USER.backup"),"corrupt");reject(()->SemanticCheckpointMaintenance.artifacts(dir,p,"identity","source","ddl"));
            Path torn=directory();var q=snapshot(torn);Files.writeString(torn.resolve("journal.log"),"torn",StandardOpenOption.APPEND);reject(()->SemanticCheckpointMaintenance.artifacts(torn,q,"identity","source","ddl"));
        });
        test("three stages preserve rows and no Word97 DDL is replayed",()->{Path dir=directory();var p=snapshot(dir);var db=new FakeDb();apply(db,dir,p);check(db.stage==3&&db.executions==3);apply(db,dir,p);check(db.executions==3);});
        test("uncertain implicit commit reconciles same journal before continuing",()->{
            for(boolean after:List.of(false,true)){Path dir=directory();var p=snapshot(dir);var db=new FakeDb();if(after)db.failAfter=1;else db.failBefore=0;
                try{apply(db,dir,p);throw new AssertionError("EXPECTED_SYNTHETIC_TRANSPORT_FAILURE");}catch(SQLException expected){}
                reject(()->apply(db,dir,p));apply(db,dir,p);check(db.stage==3&&db.executions==3);
            }
        });
        test("new durable facts or unrelated old changes never trigger a destructive rollback",()->{Path dir=directory();var p=snapshot(dir);var db=new FakeDb();apply(db,dir,p);var original=view(3);var map=new TreeMap<>(original.tables());map.put("CONTRACT_SEMANTIC_UNIT",new ResetReviewTestData.Table("CONTRACT_SEMANTIC_UNIT",List.of(new ResetReviewTestData.Column("ID",Types.VARCHAR,"VARCHAR",false)),List.of("ID"),List.of(List.of("new-durable-fact"))));db.replacement=new SemanticCheckpointMaintenance.View(map,original.facts(),3);reject(()->apply(db,dir,p));check(db.executions==3);
            var changed=new TreeMap<>(original.tables());var account=changed.get("SYS_USER");var row=new ArrayList<>(account.rows.get(0));row.set(1,"changed protected fact");changed.put("SYS_USER",new ResetReviewTestData.Table("SYS_USER",account.columns,account.pk,List.of(row)));db.replacement=new SemanticCheckpointMaintenance.View(changed,original.facts(),3);reject(()->apply(db,dir,p));check(db.executions==3);
        });
        System.out.println("SEMANTIC_MAINTENANCE_OFFLINE=PASS TESTS="+tests+" DATABASE_CONNECTIONS=0");
    }
}
