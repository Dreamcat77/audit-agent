import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Controlled merged-release schema preflight. No business DML or data resets. */
public class ContractMergedDeploymentSchema {
    static final List<String> TABLES=List.of(ContractLocalMigration.JOB,ContractLocalMigration.ATTEMPT);
    static void need(boolean condition,String reason){if(!condition)throw new IllegalStateException(reason);}
    static List<String> plan(int jobs,String fact,String guard,Map<String,String> word,List<String> jobSql) {
        need(jobs==0||jobs==4,"UNOWNED_PARTIAL_JOB_SCHEMA");
        need(guard.equals("MISSING")&&Set.of("OLD","NEW").contains(fact),"UNOWNED_PARTIAL_FACT_SCHEMA");
        var result=new ArrayList<String>();
        if(jobs==0)result.addAll(jobSql);
        result.addAll(ContractLocalMigration.factSteps(fact,guard));
        for(String table:Word97LocalMigration.TABLES) {
            String state=word.get(table);
            need(Set.of("OLD","NEW").contains(state),"UNOWNED_WORD97_SCHEMA");
            result.addAll(Word97LocalMigration.steps(table,state,"MISSING"));
        }
        need(result.stream().allMatch(s->s.startsWith("CREATE TABLE ")||s.startsWith("CREATE INDEX ")||s.startsWith("ALTER TABLE ")),"UNAPPROVED_DDL");
        return List.copyOf(result);
    }
    static ResetReviewTestData.Catalog catalog(Connection c)throws Exception {return catalog(c,TABLES);}
    static ResetReviewTestData.Catalog catalog(Connection c,List<String> requestedTables)throws Exception {
        var tables=new TreeMap<String,ResetReviewTestData.Table>();
        var fks=new ArrayList<ResetReviewTestData.Fk>();
        for(String table:requestedTables) {
            need(table.matches("[A-Z0-9_]+"),"CATALOG_IDENTIFIER_INVALID");
            var objects=ResetReviewTestData.query(c,"SELECT OBJECT_TYPE FROM USER_OBJECTS WHERE OBJECT_NAME='"+table+"'");
            if(objects.isEmpty())continue;
            need(objects.size()==1&&objects.get(0).get(0).equals("TABLE"),"JOB_OBJECT_NAME_CONFLICT");
            var columns=new ArrayList<ResetReviewTestData.Column>();
            try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table+" WHERE 1=0")) {
                var m=r.getMetaData();
                for(int i=1;i<=m.getColumnCount();i++)columns.add(new ResetReviewTestData.Column(m.getColumnName(i),m.getColumnType(i),m.getColumnTypeName(i),m.isNullable(i)!=ResultSetMetaData.columnNoNulls));
            }
            tables.put(table,new ResetReviewTestData.Table(table,columns,List.of(),List.of()));
            for(var key:ResetReviewTestData.query(c,"SELECT F.CONSTRAINT_NAME,P.TABLE_NAME,F.STATUS,F.DELETE_RULE,F.R_OWNER FROM USER_CONSTRAINTS F LEFT JOIN ALL_CONSTRAINTS P ON F.R_OWNER=P.OWNER AND F.R_CONSTRAINT_NAME=P.CONSTRAINT_NAME WHERE F.CONSTRAINT_TYPE='R' AND F.TABLE_NAME='"+table+"'")) {
                need("ENABLED".equals(key.get(2))&&"NO ACTION".equals(key.get(3))&&c.getSchema().equals(key.get(4))&&key.get(1)!=null,"FOREIGN_KEY_AUTHORITY_INVALID");
                var childCols=new ArrayList<String>();var parentCols=new ArrayList<String>();
                for(var columnsRow:ResetReviewTestData.query(c,"SELECT FC.COLUMN_NAME,PC.COLUMN_NAME FROM USER_CONSTRAINTS F JOIN USER_CONS_COLUMNS FC ON F.CONSTRAINT_NAME=FC.CONSTRAINT_NAME JOIN ALL_CONS_COLUMNS PC ON F.R_OWNER=PC.OWNER AND F.R_CONSTRAINT_NAME=PC.CONSTRAINT_NAME AND FC.POSITION=PC.POSITION WHERE F.CONSTRAINT_NAME='"+key.get(0)+"' ORDER BY FC.POSITION")) {
                    childCols.add(columnsRow.get(0));parentCols.add(columnsRow.get(1));
                }
                need(!childCols.isEmpty(),"FOREIGN_KEY_COLUMNS_UNKNOWN");
                fks.add(new ResetReviewTestData.Fk(key.get(0),table,key.get(1),childCols,parentCols));
            }
        }
        return new ResetReviewTestData.Catalog(tables,fks,List.of(),"");
    }
    static Map<String,String> words(Connection c)throws Exception {
        var states=new LinkedHashMap<String,String>();
        for(String table:Word97LocalMigration.TABLES) {
            need(Word97LocalMigration.definition(c,table,Word97LocalMigration.name(table)+"_DOC_GUARD").equals("MISSING"),"UNOWNED_WORD97_GUARD");
            states.put(table,Word97LocalMigration.definition(c,table,Word97LocalMigration.name(table)));
        }
        return states;
    }
    static Map<String,Long> counts(Connection c)throws Exception {
        var counts=new TreeMap<String,Long>();
        for(String table:List.of("PROJECT_FILE","PROJECT_FILE_PARSE_RESULT","CONTRACT_MODEL_CALL_AUDIT"))
            try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();counts.put(table,r.getLong(1));}
        return counts;
    }
    public static void main(String[] args) {
        try {run(args);}catch(Exception e){System.err.println("MERGED_SCHEMA=BLOCKED reasonCode="+(e instanceof IllegalStateException&&Objects.toString(e.getMessage(),"").matches("[A-Z0-9_]+")?e.getMessage():"DATABASE_OR_PLATFORM_ERROR")+" errorClass="+e.getClass().getSimpleName()+" dbCode="+(e instanceof SQLException s?s.getErrorCode():0));for(var frame:Arrays.stream(e.getStackTrace()).limit(4).toList())System.err.println("CODE_LOCATION="+frame);System.exit(1);}
    }
    static void run(String[] args)throws Exception {
        need(args.length==3&&Set.of("check","apply","isolated").contains(args[0]),"ARGUMENTS");
        Path root=Path.of(args[1]),record=Path.of(args[2]);
        var jobs=ContractLocalMigration.approvedSql(root);
        try(var c=DriverManager.getConnection(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"))) {
            need(c.getMetaData().getDatabaseProductName().toUpperCase(Locale.ROOT).contains("DM"),"NOT_DM8");
            need(!Set.of("SYSDBA","SYSAUDITOR").contains(c.getMetaData().getUserName().toUpperCase(Locale.ROOT)),"ADMIN_ACCOUNT_FORBIDDEN");
            if(args[0].equals("isolated")) {
                String tag=UUID.randomUUID().toString().replace("-", "").substring(0,8).toUpperCase(Locale.ROOT);
                String job="AXJ_"+tag,attempt="AXA_"+tag;
                var temporary=new ArrayList<String>();
                for(String sql:jobs) {
                    sql=sql.replace(ContractLocalMigration.ATTEMPT,attempt).replace(ContractLocalMigration.JOB,job);
                    var matcher=java.util.regex.Pattern.compile("(?:CONSTRAINT |CREATE INDEX )([A-Z0-9_]+)").matcher(sql);
                    var replacements=new LinkedHashMap<String,String>();
                    while(matcher.find())replacements.put(matcher.group(1),"AX_"+tag+"_"+replacements.size()+"_"+temporary.size());
                    for(var e:replacements.entrySet())sql=sql.replace(e.getKey(),e.getValue());
                    temporary.add(sql);
                }
                var created=new ArrayList<String>();
                try {
                    for(String sql:temporary) {
                        try(var s=c.createStatement()){s.setQueryTimeout(30);s.execute(sql);}
                        if(sql.startsWith("CREATE TABLE "))created.add(sql.split("\\s+")[2]);
                    }
                    var cat=catalog(c,List.of(job,attempt));
                    ContractLocalMigration.tableMatches(c,cat,job,temporary.get(0));
                    ContractLocalMigration.tableMatches(c,cat,attempt,temporary.get(3));
                } finally {
                    Collections.reverse(created);
                    for(String table:created) {
                        need(table.equals(job)||table.equals(attempt),"TEMPORARY_CLEANUP_TARGET_INVALID");
                        try(var s=c.createStatement()){s.execute("DROP TABLE "+table);}
                    }
                }
                System.out.println("MERGED_SCHEMA_REAL_DM8_ISOLATED_TABLE_KEYS_CHECKS=PASS TEMPORARY_TABLES_REMOVED=YES");return;
            }
            c.setReadOnly(true);
            int jobStage=ContractLocalMigration.jobStage(c,catalog(c),jobs);
            String fact=ContractLocalMigration.definition(c,ContractLocalMigration.FACT_CHECK),guard=ContractLocalMigration.definition(c,ContractLocalMigration.GUARD);
            var word=words(c);var steps=plan(jobStage,fact,guard,word,jobs);
            System.out.println("MERGED_SCHEMA_PREFLIGHT=PASS jobStage="+jobStage+" fact="+fact+" word97="+word.values()+" statements="+steps.size());
            if(args[0].equals("check")&&"1".equals(System.getenv("AUDIT_REQUIRE_CURRENT_CONTRACT_SCHEMA")))
                need(steps.isEmpty(),"CONTRACT_SCHEMA_UPGRADE_REQUIRED");
            if(args[0].equals("check")||steps.isEmpty())return;
            need("1".equals(System.getenv("AUDIT_ALLOW_TEST_SCHEMA_UPGRADE")),"EXPLICIT_TEST_SCHEMA_GATE_REQUIRED");
            need(!Files.exists(record),"EXISTING_DDL_RECORD_REQUIRES_INVESTIGATION");Files.createDirectory(record);
            Files.writeString(record.resolve("plan.sql"),String.join(";\n",steps)+";\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            var before=counts(c);
            var definitions=new ArrayList<String>();
            for(String table:List.of("PROJECT_FILE","PROJECT_FILE_PARSE_RESULT","CONTRACT_MODEL_CALL_AUDIT"))
                definitions.addAll(ResetReviewTestData.query(c,"SELECT CONSTRAINT_NAME,STATUS,SEARCH_CONDITION FROM USER_CONSTRAINTS WHERE TABLE_NAME='"+table+"'").stream().map(Object::toString).toList());
            Files.write(record.resolve("catalog-before.txt"),definitions,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            Files.writeString(record.resolve("rows-before.txt"),before.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            c.setReadOnly(false);
            int ordinal=0;
            for(String step:steps) {
                Files.writeString(record.resolve("journal.txt"),"INTENT "+(++ordinal)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                try(var s=c.createStatement()){s.setQueryTimeout(60);s.execute(step);}
                if(step.startsWith("ALTER TABLE CONTRACT_MODEL_CALL_AUDIT"))
                    ContractLocalMigration.factStage(c);
                if(step.startsWith("ALTER TABLE PROJECT_FILE ")) {
                    String state=Word97LocalMigration.definition(c,"PROJECT_FILE",Word97LocalMigration.name("PROJECT_FILE"));
                    String temp=Word97LocalMigration.definition(c,"PROJECT_FILE",Word97LocalMigration.name("PROJECT_FILE")+"_DOC_GUARD");
                    Word97LocalMigration.steps("PROJECT_FILE",state,temp);
                }
                if(step.startsWith("ALTER TABLE PROJECT_FILE_PARSE_RESULT ")) {
                    String state=Word97LocalMigration.definition(c,"PROJECT_FILE_PARSE_RESULT",Word97LocalMigration.name("PROJECT_FILE_PARSE_RESULT"));
                    String temp=Word97LocalMigration.definition(c,"PROJECT_FILE_PARSE_RESULT",Word97LocalMigration.name("PROJECT_FILE_PARSE_RESULT")+"_DOC_GUARD");
                    Word97LocalMigration.steps("PROJECT_FILE_PARSE_RESULT",state,temp);
                }
                Files.writeString(record.resolve("journal.txt"),"ACK "+ordinal+"\n",StandardOpenOption.APPEND);
            }
            need(ContractLocalMigration.jobStage(c,catalog(c),jobs)==4,"FINAL_JOB_SCHEMA_MISMATCH");
            need(ContractLocalMigration.factStage(c)==4,"FINAL_FACT_SCHEMA_MISMATCH");
            need(words(c).values().stream().allMatch("NEW"::equals),"FINAL_WORD97_SCHEMA_MISMATCH");
            need(before.equals(counts(c)),"EXISTING_ROW_COUNT_CHANGED");
            Files.writeString(record.resolve("journal.txt"),"VERIFIED NO_BUSINESS_DML\n",StandardOpenOption.APPEND);
            System.out.println("MERGED_SCHEMA=VERIFIED NO_BUSINESS_DML EXISTING_ROW_COUNTS_UNCHANGED");
        }
    }
}
