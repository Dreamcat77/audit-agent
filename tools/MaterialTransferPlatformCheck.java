import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.material.transfer.MaterialTransfer.*;
import com.fuquan.audit.integration.dm8.Dm8MaterialTransferRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Additive V054 guard and real DM8 rollback probe. Never repairs existing objects. */
public class MaterialTransferPlatformCheck {
    static String stage="ARGUMENTS",table="NONE";
    static void need(boolean condition,String code){if(!condition)throw new IllegalStateException(code);}
    public static void main(String[] args){try{run(args);}catch(Exception failure){SQLException sql=null;for(Throwable c=failure;c!=null;c=c.getCause())if(c instanceof SQLException s)sql=s;String code=failure instanceof IllegalStateException&&Objects.toString(failure.getMessage(),"").matches("[A-Z0-9_]+")?failure.getMessage():"DATABASE_OR_PLATFORM_ERROR";System.err.println("TRANSFER_PLATFORM_FAILED table="+table+" stage="+stage+" reasonCode="+code+" type="+failure.getClass().getSimpleName()+" dbCode="+(sql==null?"NONE":sql.getErrorCode()));System.exit(1);}}
    static void run(String[] args)throws Exception {
        need(args.length==2&&Set.of("check","apply","probe").contains(args[0]),"ARGUMENTS");
        var ds=new DriverManagerDataSource(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"));var jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(20);
        String sql=Files.readString(Path.of(args[1])).replaceAll("(?m)^--.*$","");
        String expected="CREATE TABLE MATERIAL_TRANSFER (ID VARCHAR(36) NOT NULL PRIMARY KEY,ACTOR_ID VARCHAR(64) NOT NULL,KIND VARCHAR(24) NOT NULL,OPERATION_ID VARCHAR(36) NOT NULL,SPEC_JSON CLOB NOT NULL,STATUS VARCHAR(16) NOT NULL,LEASE_TOKEN VARCHAR(36),LEASE_UNTIL TIMESTAMP,RESULT_JSON CLOB,CREATED_AT TIMESTAMP NOT NULL);CREATE TABLE MATERIAL_TRANSFER_PART (TRANSFER_ID VARCHAR(36) NOT NULL,PART_INDEX INT NOT NULL,FILE_SIZE BIGINT NOT NULL,SHA256 VARCHAR(64) NOT NULL,STORAGE_KEY VARCHAR(64) NOT NULL,PRIMARY KEY(TRANSFER_ID,PART_INDEX),FOREIGN KEY(TRANSFER_ID) REFERENCES MATERIAL_TRANSFER(ID));CREATE INDEX IX_MATERIAL_TRANSFER_ACTOR ON MATERIAL_TRANSFER(ACTOR_ID,CREATED_AT);";
        need(sql.replaceAll("\\s+","").equalsIgnoreCase(expected.replaceAll("\\s+","")),"UNEXPECTED_MIGRATION_SQL");
        for(String name:List.of("MATERIAL_TRANSFER","MATERIAL_TRANSFER_PART")) {
            table=name;stage="OBJECTS";
            int objects=jdbc.queryForObject("SELECT COUNT(*) FROM USER_OBJECTS WHERE OBJECT_NAME=?",Integer.class,name);
            if(objects==0) {
                need(jdbc.queryForObject("SELECT COUNT(*) FROM ALL_SYNONYMS WHERE OWNER='PUBLIC' AND SYNONYM_NAME=?",Integer.class,name)==0,"PUBLIC_SYNONYM_CONFLICT");
                if(!args[0].equals("apply")){System.out.println("V054="+name+"_NOT_APPLIED");need(args[0].equals("check"),"SCHEMA_NOT_APPLIED");return;}
                stage="CREATE";String statement=Arrays.stream(sql.split(";")).filter(s->s.stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE TABLE "+name+" (")).findFirst().orElseThrow();jdbc.execute(statement);
            }
            verify(jdbc,name);
        }
        table="MATERIAL_TRANSFER";stage="INDEX";
        var index=jdbc.queryForList("SELECT TABLE_NAME,UNIQUENESS FROM USER_INDEXES WHERE INDEX_NAME='IX_MATERIAL_TRANSFER_ACTOR'");
        if(index.isEmpty()&&args[0].equals("check")){System.out.println("V054=INDEX_NOT_APPLIED");return;}
        if(index.isEmpty()&&args[0].equals("apply"))jdbc.execute("CREATE INDEX IX_MATERIAL_TRANSFER_ACTOR ON MATERIAL_TRANSFER(ACTOR_ID,CREATED_AT)");
        need(jdbc.queryForObject("SELECT COUNT(*) FROM USER_INDEXES WHERE INDEX_NAME='IX_MATERIAL_TRANSFER_ACTOR' AND TABLE_NAME='MATERIAL_TRANSFER' AND UNIQUENESS='NONUNIQUE'",Integer.class)==1,"INDEX_DRIFT");
        need(jdbc.queryForList("SELECT COLUMN_NAME FROM USER_IND_COLUMNS WHERE INDEX_NAME='IX_MATERIAL_TRANSFER_ACTOR' ORDER BY COLUMN_POSITION",String.class).equals(List.of("ACTOR_ID","CREATED_AT")),"INDEX_ORDER_DRIFT");
        System.out.println("V054=SCHEMA_MATCHING");
        if(args[0].equals("probe"))probe(jdbc,ds);
    }
    static void verify(JdbcTemplate jdbc,String name) {
        stage="SCHEMA";need(jdbc.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME=?",Integer.class,name)==1,"NON_TABLE_OR_SYNONYM");
        Map<String,String> columns=name.equals("MATERIAL_TRANSFER")?Map.of("ID","VARCHAR:36:N","ACTOR_ID","VARCHAR:64:N","KIND","VARCHAR:24:N","OPERATION_ID","VARCHAR:36:N","SPEC_JSON","CLOB:0:N","STATUS","VARCHAR:16:N","LEASE_TOKEN","VARCHAR:36:Y","LEASE_UNTIL","TIMESTAMP:0:Y","RESULT_JSON","CLOB:0:Y","CREATED_AT","TIMESTAMP:0:N"):Map.of("TRANSFER_ID","VARCHAR:36:N","PART_INDEX","INT:0:N","FILE_SIZE","BIGINT:0:N","SHA256","VARCHAR:64:N","STORAGE_KEY","VARCHAR:64:N");
        var observed=jdbc.queryForList("SELECT COLUMN_NAME,DATA_TYPE,DATA_LENGTH,NULLABLE,DATA_DEFAULT FROM USER_TAB_COLUMNS WHERE TABLE_NAME=?",name);need(observed.size()==columns.size(),"COLUMN_COUNT_DRIFT");
        for(var c:observed){String type=c.get("DATA_TYPE").toString();String signature=type+":"+(type.equals("VARCHAR")?c.get("DATA_LENGTH"):0)+":"+c.get("NULLABLE");if(!signature.equals(columns.get(c.get("COLUMN_NAME"))))System.out.println("COLUMN_OBSERVED name="+c.get("COLUMN_NAME")+" type="+type+" length="+c.get("DATA_LENGTH")+" nullable="+c.get("NULLABLE"));need(signature.equals(columns.get(c.get("COLUMN_NAME"))),"COLUMN_DRIFT_"+c.get("COLUMN_NAME"));need(c.get("DATA_DEFAULT")==null,"DEFAULT_DRIFT");}
        int pk=0,fk=0;
        for(var c:jdbc.queryForList("SELECT CONSTRAINT_NAME,CONSTRAINT_TYPE,STATUS,SEARCH_CONDITION,R_OWNER,R_CONSTRAINT_NAME FROM USER_CONSTRAINTS WHERE TABLE_NAME=?",name)){
            need("ENABLED".equals(c.get("STATUS")),"DISABLED_CONSTRAINT");String type=c.get("CONSTRAINT_TYPE").toString();
            var fields=jdbc.queryForList("SELECT COLUMN_NAME FROM USER_CONS_COLUMNS WHERE CONSTRAINT_NAME=? ORDER BY POSITION",String.class,c.get("CONSTRAINT_NAME"));
            if(type.equals("P")){pk++;need(fields.equals(name.equals("MATERIAL_TRANSFER")?List.of("ID"):List.of("TRANSFER_ID","PART_INDEX")),"PRIMARY_KEY_DRIFT");}
            else if(type.equals("R")) {fk++;need(name.equals("MATERIAL_TRANSFER_PART")&&fields.equals(List.of("TRANSFER_ID")),"FOREIGN_KEY_DRIFT");need(Objects.equals(c.get("R_OWNER"),jdbc.queryForObject("SELECT USER FROM DUAL",String.class)),"FOREIGN_OWNER_DRIFT");need(jdbc.queryForList("SELECT TABLE_NAME||':'||COLUMN_NAME FROM USER_CONS_COLUMNS WHERE CONSTRAINT_NAME=? ORDER BY POSITION",String.class,c.get("R_CONSTRAINT_NAME")).equals(List.of("MATERIAL_TRANSFER:ID")),"FOREIGN_TARGET_DRIFT");}
            else {need(type.equals("C"),"EXTRA_CONSTRAINT");String check=Objects.toString(c.get("SEARCH_CONDITION"),"").replaceAll("[\\s\\\"()]","").toUpperCase(Locale.ROOT);need(columns.entrySet().stream().filter(e->e.getValue().endsWith(":N")).anyMatch(e->check.equals(e.getKey()+"ISNOTNULL")||check.equals("NOT"+e.getKey()+"ISNULL")),"EXTRA_CHECK_DRIFT");}
        }
        need(pk==1&&fk==(name.equals("MATERIAL_TRANSFER_PART")?1:0),"KEY_COUNT_DRIFT");
    }
    static void probe(JdbcTemplate jdbc,DriverManagerDataSource ds) {
        table="MATERIAL_TRANSFER";stage="REPOSITORY_PROBE";var repo=new Dm8MaterialTransferRepository(jdbc,new ObjectMapper());var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));UUID id=UUID.randomUUID(),operation=UUID.randomUUID();Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var spec=new Spec("合成.pdf","application/pdf",1,"a".repeat(64),List.of("a".repeat(64)),null,"2026",null,null);
        var session=new Session(id,"synthetic-transfer", "budget",operation,spec,"OPEN",null,null,null,now);
        tx.executeWithoutResult(state->{repo.insert(session);need(repo.find(id).orElseThrow().spec().equals(spec),"SPEC_CLOB_ROUNDTRIP");repo.insertPart(id,new Part(0,1,"a".repeat(64),"b".repeat(64)));need(repo.parts(id).size()==1,"PART_ROUNDTRIP");
            boolean duplicate=false;try{repo.insertPart(id,new Part(0,1,"a".repeat(64),"b".repeat(64)));}catch(org.springframework.dao.DuplicateKeyException expected){duplicate=true;}need(duplicate,"DUPLICATE_NOT_REJECTED");
            need(repo.claim(id,"old",now.minusSeconds(300),now.minusSeconds(120)),"CLAIM");need(repo.claim(id,"next",now,now.plusSeconds(120)),"EXPIRED_CLAIM");need(!repo.renew(id,"old",now.plusSeconds(1000))&&!repo.complete(id,"old","{}"),"STALE_TOKEN_FENCE");repo.release(id,"old");need(repo.find(id).orElseThrow().status().equals("ASSEMBLING"),"STALE_RELEASE");
            String result="{\"synthetic\":\""+"合成".repeat(30000)+"\"}";need(repo.complete(id,"next",result),"COMPLETE");need(repo.find(id).orElseThrow().resultJson().equals(result),"RESULT_CLOB_ROUNDTRIP");need(!repo.claim(id,"late",now.plusSeconds(500),now.plusSeconds(600)),"COMPLETED_RECLAIM");state.setRollbackOnly();});
        need(repo.find(id).isEmpty()&&repo.parts(id).isEmpty(),"ROLLBACK");System.out.println("DM8_TRANSFER_CLOB_PART_DUPLICATE_EXPIRED_LEASE_TOKEN_FENCE_ROLLBACK=PASS");
    }
}
