package com.fuquan.audit.procurement.autocase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.AuditApplication;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.integration.ai.fastapi.FastApiMaterialFieldAdapter;
import com.fuquan.audit.integration.dm8.*;
import java.nio.file.*;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.client.RestClient;

/** Test-only executable: real HTTP/worker/DM8 with local account seam and a durable test audit journal. */
public class AutomaticCaseDm8Harness {
    public static void main(String[] args) {
        if(!"ISOLATED_AUTOCASE2".equals(System.getenv("AUDIT_CASE_TEST_DB_CONFIRM")))throw new IllegalStateException("isolated database required");
        new SpringApplicationBuilder(AuditApplication.class,AcceptanceConfiguration.class).profiles("local")
                .properties(Map.of("server.address","127.0.0.1","server.port","19131",
                        "audit.procurement.auto-case.enabled","true","audit.procurement.auto-case.scan-delay-ms","500",
                        "logging.level.com.fuquan.audit.common.observability.FlowDebugLogger","INFO"))
                .run(args);
    }
    @TestConfiguration @EnableTransactionManagement
    public static class AcceptanceConfiguration {
        @Bean @Primary java.time.Clock acceptanceClock() {
            long offset=Long.parseLong(System.getenv().getOrDefault("AUDIT_CASE_TEST_CLOCK_OFFSET_SECONDS","0"));
            return java.time.Clock.offset(java.time.Clock.systemUTC(),java.time.Duration.ofSeconds(offset));
        }
        @Bean DataSource acceptanceDataSource() {
            String url=System.getenv("AUDIT_CASE_TEST_DB_URL");
            if(url==null||!url.matches("jdbc:dm://127\\.0\\.0\\.1:15288"))throw new IllegalArgumentException("dedicated test proxy required");
            return new DriverManagerDataSource(url,System.getenv("AUDIT_CASE_TEST_DB_USERNAME"),System.getenv("AUDIT_CASE_TEST_DB_PASSWORD"));
        }
        @Bean @Primary PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds)throws Exception {
            var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);
            var config=new org.apache.ibatis.session.Configuration();config.addMapper(AutoCaseMapper.class);factory.setConfiguration(config);
            return factory.getObject();
        }
        @Bean @Primary AutoCaseMapper acceptanceAutoCaseMapper(SqlSessionFactory factory){return new SqlSessionTemplate(factory).getMapper(AutoCaseMapper.class);}
        @Bean @Primary AuditCaseRepository acceptanceRepository(AutoCaseMapper mapper,ObjectMapper json){return new Dm8AuditCaseRepository(mapper,json);}
        @Bean @Primary OperationAuditPort acceptanceAudit(DataSource ds,ObjectMapper json) {
            var jdbc=new JdbcTemplate(ds);
            // This journal belongs only to the acceptance harness, never to a production profile.
            if(jdbc.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='AUTO_CASE_TEST_AUDIT'",Integer.class)==0)
                jdbc.execute("CREATE TABLE AUTO_CASE_TEST_AUDIT(ID VARCHAR(36) PRIMARY KEY,EVENT_JSON CLOB NOT NULL)");
            return event->{
                try {
                    String payload=json.writeValueAsString(event);
                    jdbc.update(c->{var p=c.prepareStatement("INSERT INTO AUTO_CASE_TEST_AUDIT(ID,EVENT_JSON) VALUES(?,?)");
                        p.setString(1,UUID.randomUUID().toString());p.setCharacterStream(2,new java.io.StringReader(payload),payload.length());return p;});
                }catch(java.io.IOException e){throw new IllegalStateException(e);}
            };
        }
        @Bean @Primary MaterialFieldAiPort acceptanceAi() {
            String mode=System.getenv().getOrDefault("AUDIT_CASE_TEST_MODEL_MODE","simulated");
            if(mode.equals("actual"))return new FastApiMaterialFieldAdapter(RestClient.builder().baseUrl(System.getenv("AUDIT_CASE_REAL_AI_URL")).build());
            if(!mode.equals("simulated"))throw new IllegalArgumentException("unknown model mode");
            return (kind,id,sha,segments)->{
                String gate=System.getenv("AUDIT_CASE_TEST_MODEL_GATE");
                if(gate!=null) {
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
                    while(!Files.exists(Path.of(gate))) {
                        if(System.nanoTime()>deadline)throw new IllegalStateException("test gate timeout");
                        try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    }
                }
                return AuditCaseIntegrationTest.sourceFixture(segments);
            };
        }
    }
}
