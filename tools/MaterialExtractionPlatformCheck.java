import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import com.fuquan.audit.common.material.application.port.MaterialExtractionLedger.Entry;
import com.fuquan.audit.integration.dm8.Dm8MaterialExtractionLedger;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** V055 guard and isolated real DM8 probe. Never repairs existing objects. */
public class MaterialExtractionPlatformCheck {
    static final String TABLE="MATERIAL_EXTRACTION_LEDGER";
    static String stage="ARGUMENTS";
    static void need(boolean value,String code){if(!value)throw new IllegalStateException(code);}
    static String normalize(String value){return value.replaceAll("[\\s\"()]","").toUpperCase(Locale.ROOT);}
    public static void main(String[] args) {
        try {run(args);}catch(Exception failure) {
            SQLException sql=null;for(Throwable c=failure;c!=null;c=c.getCause())if(c instanceof SQLException s)sql=s;
            String reason=failure instanceof IllegalStateException&&Objects.toString(failure.getMessage(),"").matches("[A-Z0-9_]+")?failure.getMessage():"DATABASE_OR_PLATFORM_ERROR";
            System.err.println("EXTRACTION_PLATFORM_FAILED stage="+stage+" reasonCode="+reason+" type="+failure.getClass().getSimpleName()+" dbCode="+(sql==null?"NONE":sql.getErrorCode()));System.exit(1);
        }
    }
    static void run(String[] args)throws Exception {
        need(args.length==2&&Set.of("check","apply","isolated-probe").contains(args[0]),"ARGUMENTS");
        var ds=new DriverManagerDataSource(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(20);
        String sql=Files.readString(Path.of(args[1])).replaceAll("(?m)^--.*$","");
        String expected="CREATE TABLE MATERIAL_EXTRACTION_LEDGER (ID VARCHAR(36) NOT NULL,SOURCE_HASH VARCHAR(64) NOT NULL,POLICY_HASH VARCHAR(64) NOT NULL,UNIT_COUNT INT NOT NULL,NEXT_UNIT INT DEFAULT 0 NOT NULL,CALL_COUNT INT DEFAULT 0 NOT NULL,USED_MILLIS BIGINT DEFAULT 0 NOT NULL,STATUS VARCHAR(16) NOT NULL,CALL_TOKEN VARCHAR(36),UPDATED_AT TIMESTAMP NOT NULL,RESULTS_JSON CLOB NOT NULL,REASON_CODE VARCHAR(64),CONSTRAINT PK_MATERIAL_EXTRACTION_LEDGER PRIMARY KEY(ID),CONSTRAINT CK_MATERIAL_EXTRACTION_STATUS CHECK(STATUS IN ('READY','CALLING','COMPLETE','UNKNOWN')),CONSTRAINT CK_MATERIAL_EXTRACTION_BUDGET CHECK(UNIT_COUNT BETWEEN 1 AND 512 AND NEXT_UNIT BETWEEN 0 AND UNIT_COUNT AND CALL_COUNT BETWEEN 0 AND 512 AND USED_MILLIS BETWEEN 0 AND 900000))";
        String ddl=sql.split(";")[0].strip();
        need(ddl.replaceAll("\\s+","").equalsIgnoreCase(expected.replaceAll("\\s+","")),"MIGRATION_SQL_DRIFT");
        if(args[0].equals("isolated-probe")) {
            // UUID table name and constraint names are allocated only for this probe.
            String temporary="AXP_"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT);
            stage="ISOLATED_CREATE";
            jdbc.execute(ddl.replace(TABLE,temporary).replace("CK_MATERIAL_EXTRACTION_STATUS","CK_"+temporary+"_S").replace("CK_MATERIAL_EXTRACTION_BUDGET","CK_"+temporary+"_B"));
            try {
                try(var connection=ds.getConnection()) {
                    need(!MaterialDeadlineMigration.migrate(connection,temporary,false),"DEADLINE_CHECK_MUTATED_SCHEMA");
                    need(MaterialDeadlineMigration.migrate(connection,temporary,true),"DEADLINE_MIGRATION_FAILED");
                    need(MaterialDeadlineMigration.migrate(connection,temporary,true),"DEADLINE_MIGRATION_NOT_IDEMPOTENT");
                }
                verify(jdbc,temporary);probe(new ProbeJdbc(ds,temporary));
                stage="ISOLATED_DEADLINE_DRIFT";
                jdbc.execute("ALTER TABLE "+temporary+" DROP COLUMN DEADLINE_AT");
                jdbc.execute("ALTER TABLE "+temporary+" ADD DEADLINE_AT VARCHAR(32)");
                try(var connection=ds.getConnection()) {
                    boolean rejected=false;
                    try {MaterialDeadlineMigration.migrate(connection,temporary,true);}
                    catch(IllegalStateException drift){rejected="V057_DEADLINE_SCHEMA_DRIFT".equals(drift.getMessage());}
                    need(rejected,"DEADLINE_DRIFT_NOT_REJECTED");
                }
                System.out.println("DM8_DEADLINE_MIGRATION_CHECK_APPLY_IDEMPOTENCY_DRIFT=PASS");
            }
            finally {jdbc.execute("DROP TABLE "+temporary);}
            System.out.println("ISOLATED_DM8_EXTRACTION_PROBE=PASS TEMPORARY_TABLE_REMOVED=YES APPLICATION_SCHEMA_UNCHANGED=YES");return;
        }
        stage="OBJECTS";
        int objects=jdbc.queryForObject("SELECT COUNT(*) FROM USER_OBJECTS WHERE OBJECT_NAME=?",Integer.class,TABLE);
        if(objects==0) {
            need(jdbc.queryForObject("SELECT COUNT(*) FROM ALL_SYNONYMS WHERE OWNER='PUBLIC' AND SYNONYM_NAME=?",Integer.class,TABLE)==0,"PUBLIC_SYNONYM_CONFLICT");
            if(args[0].equals("check")){System.out.println("V055=NOT_APPLIED");return;}
            for(String name:List.of("PK_MATERIAL_EXTRACTION_LEDGER","CK_MATERIAL_EXTRACTION_STATUS","CK_MATERIAL_EXTRACTION_BUDGET"))
                need(jdbc.queryForObject("SELECT COUNT(*) FROM USER_CONSTRAINTS WHERE CONSTRAINT_NAME=?",Integer.class,name)==0,"CONSTRAINT_NAME_CONFLICT");
            stage="CREATE";jdbc.execute(ddl);
        }
        verify(jdbc,TABLE);System.out.println("V055=SCHEMA_MATCHING");
    }
    static void verify(JdbcTemplate jdbc,String table) {
        stage="SCHEMA";
        need(jdbc.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME=?",Integer.class,table)==1,"NON_TABLE_OR_SYNONYM");
        var expected=Map.ofEntries(Map.entry("ID","VARCHAR:36:N"),Map.entry("SOURCE_HASH","VARCHAR:64:N"),Map.entry("POLICY_HASH","VARCHAR:64:N"),
            Map.entry("UNIT_COUNT","INT:0:N"),Map.entry("NEXT_UNIT","INT:0:N"),Map.entry("CALL_COUNT","INT:0:N"),Map.entry("USED_MILLIS","BIGINT:0:N"),
            Map.entry("STATUS","VARCHAR:16:N"),Map.entry("CALL_TOKEN","VARCHAR:36:Y"),Map.entry("UPDATED_AT","TIMESTAMP:0:N"),Map.entry("RESULTS_JSON","CLOB:0:N"),Map.entry("REASON_CODE","VARCHAR:64:Y"));
        var columns=jdbc.queryForList("SELECT COLUMN_NAME,DATA_TYPE,DATA_LENGTH,NULLABLE,DATA_DEFAULT FROM USER_TAB_COLUMNS WHERE TABLE_NAME=?",table);
        need(columns.size()==expected.size()||columns.size()==expected.size()+1,"COLUMN_COUNT_DRIFT");
        for(var c:columns) {
            String name=c.get("COLUMN_NAME").toString(),type=c.get("DATA_TYPE").toString();if(type.equals("INTEGER"))type="INT";
            if(name.equals("DEADLINE_AT")){need(type.equals("TIMESTAMP")&&c.get("NULLABLE").equals("Y")&&Objects.toString(c.get("DATA_DEFAULT"),"").isBlank(),"DEADLINE_COLUMN_DRIFT");continue;}
            need((type+":"+(type.equals("VARCHAR")?c.get("DATA_LENGTH"):0)+":"+c.get("NULLABLE")).equals(expected.get(name)),"COLUMN_DRIFT_"+name);
            String def=Objects.toString(c.get("DATA_DEFAULT"),"");
            need(Set.of("NEXT_UNIT","CALL_COUNT","USED_MILLIS").contains(name)?normalize(def).equals("0"):def.isBlank(),"DEFAULT_DRIFT_"+name);
        }
        var checks=jdbc.queryForList("SELECT CONSTRAINT_NAME,CONSTRAINT_TYPE,STATUS,SEARCH_CONDITION FROM USER_CONSTRAINTS WHERE TABLE_NAME=?",table);
        int pk=0;var expressions=new HashSet<String>();
        for(var c:checks) {
            need(c.get("STATUS").equals("ENABLED"),"DISABLED_CONSTRAINT");
            if(c.get("CONSTRAINT_TYPE").equals("P")) {
                pk++;need(jdbc.queryForList("SELECT COLUMN_NAME FROM USER_CONS_COLUMNS WHERE CONSTRAINT_NAME=? ORDER BY POSITION",String.class,c.get("CONSTRAINT_NAME")).equals(List.of("ID")),"PRIMARY_KEY_DRIFT");
            } else if(c.get("CONSTRAINT_TYPE").equals("C"))expressions.add(normalize(Objects.toString(c.get("SEARCH_CONDITION"),"")));
            else throw new IllegalStateException("UNEXPECTED_CONSTRAINT");
        }
        need(pk==1,"PRIMARY_KEY_DRIFT");
        // Only these exact expressions are accepted, not generic CHECK normalization equivalence.
        need(expressions.equals(Set.of(normalize("STATUS IN ('READY','CALLING','COMPLETE','UNKNOWN')"),normalize("UNIT_COUNT BETWEEN 1 AND 512 AND NEXT_UNIT BETWEEN 0 AND UNIT_COUNT AND CALL_COUNT BETWEEN 0 AND 512 AND USED_MILLIS BETWEEN 0 AND 900000"))),"CHECK_CONSTRAINT_DRIFT");
    }
    static void probe(JdbcTemplate jdbc) {
        stage="ISOLATED_REPOSITORY";
        // DM8 TIMESTAMP persists microseconds; compare the stored deadline at that precision.
        var repo=new Dm8MaterialExtractionLedger(jdbc);var id=UUID.randomUUID();var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        stage="INSERT";repo.insert(new Entry(id,"a".repeat(64),"b".repeat(64),2,0,0,0,"READY",null,now,"[]",null));
        need(repo.find(id).orElseThrow().nextUnit()==0,"READ_INSERT");
        boolean duplicate=false;try{repo.insert(new Entry(id,"a".repeat(64),"b".repeat(64),2,0,0,0,"READY",null,now,"[]",null));}
        catch(org.springframework.dao.DataIntegrityViolationException expected){duplicate=repo.find(id).orElseThrow().sourceHash().equals("a".repeat(64));}need(duplicate,"DUPLICATE_NOT_REJECTED");
        stage="BEGIN";need(repo.begin(id,0,"owner",now),"BEGIN");need(!repo.begin(id,0,"other",now),"CONCURRENT_CALL_FENCE");
        need(!repo.acknowledge(id,0,"other",1,1,"[]",now),"STALE_TOKEN_FENCE");
        String result="[\""+"合成预算明细".repeat(10000)+"\"]";
        stage="ACKNOWLEDGE";need(repo.acknowledge(id,0,"owner",3,123,result,now),"ACKNOWLEDGE");
        var reopened=new Dm8MaterialExtractionLedger(jdbc).find(id).orElseThrow();
        need(reopened.resultsJson().equals(result)&&reopened.calls()==3&&reopened.usedMillis()==123&&reopened.nextUnit()==1&&reopened.status().equals("READY"),"REOPEN_CLOB_BUDGET");
        need(repo.begin(id,1,"second",now),"SECOND_BEGIN");repo.unknown(id,"second","EXECUTOR_LOST",now);
        need(!repo.acknowledge(id,1,"second",1,1,"[]",now)&&!repo.begin(id,1,"late",now),"UNKNOWN_LATE_RESPONSE_FENCE");
        var timeoutId=UUID.randomUUID();repo.insert(new Entry(timeoutId,"a".repeat(64),"b".repeat(64),2,0,0,0,"READY",null,now,"[]",null));
        repo.startDeadline(timeoutId,now.plusSeconds(900));repo.startDeadline(timeoutId,now.plusSeconds(1800));
        need(repo.find(timeoutId).orElseThrow().deadlineAt().equals(now.plusSeconds(900)),"DEADLINE_RESET");
        need(repo.begin(timeoutId,0,"timeout",now),"TIMEOUT_BEGIN");
        need(!repo.acknowledge(timeoutId,0,"timeout",1,1,"[]",now.plusSeconds(901)),"LATE_DEADLINE_ACK");
        need(!repo.terminate(timeoutId,"foreign","MATERIAL_CALL_TIMEOUT",now),"FOREIGN_TERMINATION");
        need(repo.terminate(timeoutId,"timeout","MATERIAL_CALL_TIMEOUT",now),"TIMEOUT_TERMINATION");
        need(repo.find(timeoutId).orElseThrow().resultsJson().equals("[]")&&!repo.acknowledge(timeoutId,0,"timeout",1,1,"[]",now),"TIMEOUT_PURGE_FENCE");
        System.out.println("DM8_EXTRACTION_CLOB_DUPLICATE_REOPEN_CUMULATIVE_BUDGET_UNKNOWN_FENCING=PASS");
    }
    static class ProbeJdbc extends JdbcTemplate {
        final String name;
        ProbeJdbc(javax.sql.DataSource ds,String name){super(ds);this.name=name;setQueryTimeout(20);}
        String sql(String s){return s.replace(TABLE,name);}
        @Override public int update(String s,Object... args){return super.update(sql(s),args);}
        @Override public int update(String s,Object[] args,int[] types){return super.update(sql(s),args,types);}
        @Override public <T> List<T> query(String s,RowMapper<T> mapper,Object... args){return super.query(sql(s),mapper,args);}
    }
}
