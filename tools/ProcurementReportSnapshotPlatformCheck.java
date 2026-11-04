import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.support.SqlLobValue;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** V056 additive guard. Existing reports and pre-review JSON are never rewritten. */
public class ProcurementReportSnapshotPlatformCheck {
    static String stage="ARGUMENTS";
    static void need(boolean value,String reason){if(!value)throw new IllegalStateException(reason);}
    public static void main(String[] args) {
        try{run(args);}catch(Exception failure){SQLException sql=null;for(Throwable c=failure;c!=null;c=c.getCause())if(c instanceof SQLException s)sql=s;
            String reason=failure instanceof IllegalStateException&&Objects.toString(failure.getMessage(),"").matches("[A-Z0-9_]+")?failure.getMessage():"DATABASE_OR_PLATFORM_ERROR";
            System.err.println("REPORT_SNAPSHOT_PLATFORM_FAILED stage="+stage+" reasonCode="+reason+" type="+failure.getClass().getSimpleName()+" dbCode="+(sql==null?"NONE":sql.getErrorCode()));System.exit(1);}
    }
    static void run(String[] args)throws Exception {
        need((args.length==2||args.length==3&&args[0].equals("isolated-probe"))&&Set.of("check","apply","isolated-probe").contains(args[0]),"ARGUMENTS");
        String statement=Files.readString(Path.of(args[1])).replaceAll("(?m)^--.*$","").split(";")[0].strip();
        need(statement.replaceAll("\\s+","").equalsIgnoreCase("ALTERTABLEPROCUREMENT_REVIEW_REPORTADDREPORT_SNAPSHOT_JSONCLOB"),"MIGRATION_SQL_DRIFT");
        var ds=new DriverManagerDataSource(System.getenv("AUDIT_DB_URL"),System.getenv("AUDIT_DB_USERNAME"),System.getenv("AUDIT_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(20);
        if(args[0].equals("isolated-probe")) {
            String table="ARP_"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT);
            stage="ISOLATED_CREATE";
            jdbc.execute("CREATE TABLE "+table+" (ID VARCHAR(36) PRIMARY KEY,PROJECT_ID VARCHAR(36),REPORT_NUMBER VARCHAR(64),RISK_LEVEL VARCHAR(16),GENERATED_TIME TIMESTAMP,OVERALL_CONCLUSION CLOB,AGENT_REVIEW_JSON CLOB)");
            try {
                String id=UUID.randomUUID().toString(),old="{\"legacy\":\"原有预审结果\"}";
                jdbc.update("INSERT INTO "+table+"(ID,AGENT_REVIEW_JSON) VALUES(?,?)",id,old);
                stage="ADDITIVE_COLUMN";jdbc.execute(statement.replace("PROCUREMENT_REVIEW_REPORT",table));verify(jdbc,table);
                String full="{\"snapshotVersion\":1,\"amount\":123456.78,\"synthetic\":\""+"原文依据".repeat(20000)+"\"}";
                jdbc.update("UPDATE "+table+" SET REPORT_SNAPSHOT_JSON=? WHERE ID=?",new Object[]{new SqlLobValue(full),id},new int[]{Types.CLOB,Types.VARCHAR});
                need(jdbc.queryForObject("SELECT AGENT_REVIEW_JSON FROM "+table+" WHERE ID=?",String.class,id).equals(old),"LEGACY_BYTES_CHANGED");
                need(jdbc.queryForObject("SELECT REPORT_SNAPSHOT_JSON FROM "+table+" WHERE ID=?",String.class,id).equals(full),"FULL_SNAPSHOT_CLOB_CHANGED");
                if(args.length==3)mapperProbe(ds,table,Path.of(args[2]),full);
            } finally {jdbc.execute("DROP TABLE "+table);}
            System.out.println("DM8_REPORT_ADDITIVE_COLUMN_LEGACY_BYTES_CLOB_ROUNDTRIP=PASS TEMPORARY_TABLE_REMOVED=YES");return;
        }
        stage="OBJECTS";
        need(jdbc.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='PROCUREMENT_REVIEW_REPORT'",Integer.class)==1,"REPORT_NOT_OWN_TABLE");
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM USER_TAB_COLUMNS WHERE TABLE_NAME='PROCUREMENT_REVIEW_REPORT' AND COLUMN_NAME='REPORT_SNAPSHOT_JSON'",Integer.class);
        if(count==0) {
            if(args[0].equals("check")){System.out.println("V056=NOT_APPLIED");return;}
            stage="ADDITIVE_COLUMN";jdbc.execute(statement);
        }
        verify(jdbc,"PROCUREMENT_REVIEW_REPORT");System.out.println("V056=SCHEMA_MATCHING");
    }
    static void mapperProbe(javax.sql.DataSource ds,String table,Path mapper,String full)throws Exception {
        stage="MYBATIS_CLOB_ROUNDTRIP";
        String xml=Files.readString(mapper).replace("PROCUREMENT_REVIEW_REPORT",table);
        var factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(ds);
        factory.setMapperLocations(new org.springframework.core.io.ByteArrayResource(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try(var session=factory.getObject().openSession(true)) {
            var repo=new com.fuquan.audit.integration.dm8.Dm8ProcurementReviewReportRepository(
                session.getMapper(com.fuquan.audit.integration.dm8.ProcurementReviewReportMapper.class));
            var project=UUID.randomUUID();var id=UUID.randomUUID();var now=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            var row=new com.fuquan.audit.procurement.domain.ProcurementReviewReport(id,project,"PCR-SYNTHETIC","WARNING",now,"合成参考结论","{\"legacy\":true}",full);
            repo.save(row);var reopened=repo.findLatestByProjectId(project).orElseThrow();
            need(reopened.equals(row),"MYBATIS_FULL_REPORT_ROUNDTRIP");
        }
        System.out.println("DM8_REPORT_REAL_MYBATIS_MAPPER_TWO_CLOBS_ROUNDTRIP=PASS");
    }
    static void verify(JdbcTemplate jdbc,String table) {
        stage="SCHEMA";
        var columns=jdbc.queryForList("SELECT DATA_TYPE,NULLABLE,DATA_DEFAULT FROM USER_TAB_COLUMNS WHERE TABLE_NAME=? AND COLUMN_NAME='REPORT_SNAPSHOT_JSON'",table);
        need(columns.size()==1,"SNAPSHOT_COLUMN_MISSING");var c=columns.get(0);
        need(c.get("DATA_TYPE").equals("CLOB")&&c.get("NULLABLE").equals("Y")&&c.get("DATA_DEFAULT")==null,"SNAPSHOT_COLUMN_DRIFT");
    }
}
