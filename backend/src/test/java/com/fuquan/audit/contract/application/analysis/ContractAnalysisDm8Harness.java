package com.fuquan.audit.contract.application.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.AuditApplication;
import com.fuquan.audit.common.application.port.*;
import com.fuquan.audit.contract.application.project.*;
import com.fuquan.audit.contract.application.project.port.*;
import com.fuquan.audit.integration.ai.fastapi.FastApiContractAnalysisAdapter;
import com.fuquan.audit.integration.dm8.*;
import com.fuquan.audit.integration.file.ProjectFileProcessingWorker;
import com.fuquan.audit.integration.ocr.fastapi.FastApiOcrAdapter;
import com.fuquan.audit.review.application.port.OcrPort;
import com.fuquan.audit.security.application.port.UserAccountRoleQueryPort;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.client.RestClient;

/** Test-only local HTTP/auth seam; Project, originals, jobs, parses, case, idempotency and audit use real isolated DM8. */
public class ContractAnalysisDm8Harness {
    public static void main(String[] args){
        if(!"ISOLATED_AUTOCASE2_CONTRACT".equals(System.getenv("AUDIT_CONTRACT_TEST_DB_CONFIRM")))throw new IllegalStateException("dedicated isolated database required");
        new SpringApplicationBuilder(AuditApplication.class,Acceptance.class).profiles("local")
            .properties(Map.of("server.address","127.0.0.1","server.port","19141","audit.contract.analysis.enabled","true",
                "audit.contract.analysis.scan-delay-ms","250","audit.contract.file-processing.local-worker-enabled","false",
                "audit.contract.file-processing.worker-enabled","true","audit.observability.flow-debug-enabled","false")).run(args);
    }
    @TestConfiguration @EnableTransactionManagement
    public static class Acceptance {
        @Bean @Primary Clock testClock(){return Clock.offset(Clock.systemUTC(),Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("AUDIT_CONTRACT_TEST_CLOCK_OFFSET","0"))));}
        @Bean DataSource testDataSource(){
            String url=System.getenv("AUDIT_CONTRACT_TEST_DB_URL");
            if(!"jdbc:dm://127.0.0.1:15288".equals(url)||!Set.of("CONTRACT_AUTO_APP","CONTRACT_CASE_QA").contains(System.getenv("AUDIT_CONTRACT_TEST_DB_USERNAME")))throw new IllegalArgumentException("isolated target unproven");
            var data=new DriverManagerDataSource(url,System.getenv("AUDIT_CONTRACT_TEST_DB_USERNAME"),System.getenv("AUDIT_CONTRACT_TEST_DB_PASSWORD"));
            var jdbc=new JdbcTemplate(data);String actor=System.getenv().getOrDefault("AUDIT_LOCAL_ACCOUNT_ID","00000000-0000-0000-0000-000000000001");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM SYS_USER WHERE ID=?",Integer.class,actor)==0)
                jdbc.update("INSERT INTO SYS_USER(ID,USERNAME,PASSWORD_HASH,REAL_NAME,STATUS,PASSWORD_CHANGED_AT,CREATED_AT,UPDATED_AT) VALUES(?,?,?,?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",actor,System.getenv("AUDIT_LOCAL_ACCOUNT_USERNAME"),System.getenv("AUDIT_LOCAL_ACCOUNT_PASSWORD_HASH"),"合成验收用户");
            return data;
        }
        @Bean @Primary PlatformTransactionManager transactionManager(DataSource data){return new DataSourceTransactionManager(data);}
        @Bean SqlSessionFactory acceptanceFactory(DataSource data)throws Exception {
            var factory=new SqlSessionFactoryBean();factory.setDataSource(data);
            var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);
            config.addMapper(ContractAnalysisMapper.class);factory.setConfiguration(config);
            var resolver=new PathMatchingResourcePatternResolver();var resources=new ArrayList<org.springframework.core.io.Resource>();
            for(String name:List.of("ProjectMapper","ProjectFileMapper","ProjectFileParseResultMapper","ProjectExtractionResultMapper","ContractCandidateMapper","IdempotencyMapper"))
                resources.addAll(List.of(resolver.getResources("classpath:mappers/dm8/"+name+".xml")));
            factory.setMapperLocations(resources.toArray(org.springframework.core.io.Resource[]::new));return factory.getObject();
        }
        @Bean @Primary ContractAnalysisRepository testCases(SqlSessionFactory factory,ObjectMapper json){return new Dm8ContractAnalysisRepository(new SqlSessionTemplate(factory).getMapper(ContractAnalysisMapper.class),json);}
        Dm8ProjectRepositories projects(SqlSessionFactory factory){var session=new SqlSessionTemplate(factory);return new Dm8ProjectRepositories(session.getMapper(ProjectMapper.class),session.getMapper(ProjectFileMapper.class),session.getMapper(ProjectExtractionResultMapper.class),session.getMapper(ContractCandidateMapper.class));}
        @Bean @Primary ProjectRepository testProjects(SqlSessionFactory factory){return projects(factory).projectRepository();}
        @Bean @Primary ProjectFileRepository testFiles(SqlSessionFactory factory){return projects(factory).projectFileRepository();}
        @Bean @Primary ExtractionResultRepository testExtractions(SqlSessionFactory factory){return projects(factory).extractionResultRepository();}
        @Bean @Primary ContractCandidateRepository testCandidates(SqlSessionFactory factory){return projects(factory).contractCandidateRepository();}
        @Bean @Primary ProjectFileParseResultRepository testParses(SqlSessionFactory factory){return new Dm8ProjectFileParseResultRepository(new SqlSessionTemplate(factory).getMapper(ProjectFileParseResultMapper.class));}
        @Bean @Primary ProjectFileJobRepository testJobs(DataSource data){return new Dm8ProjectFileJobRepository(new JdbcTemplate(data));}
        @Bean @Primary IdempotencyPort testIdempotency(SqlSessionFactory factory){return new Dm8IdempotencyAdapter(new SqlSessionTemplate(factory).getMapper(IdempotencyMapper.class));}
        @Bean ProjectFileProcessingWorker durableFileWorker(ProjectFileJobRepository jobs,ProjectFileProcessingService service,UserAccountRoleQueryPort users,Clock clock){return new ProjectFileProcessingWorker(jobs,service,users,clock);}
        @Bean @Primary ContractAnalysisAiPort testAi(){
            String mode=System.getenv().getOrDefault("AUDIT_CONTRACT_TEST_MODEL_MODE","actual");
            if(mode.equals("unavailable"))return request->{throw new org.springframework.web.client.HttpServerErrorException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);};
            if(mode.equals("unknown"))return request->{throw new org.springframework.web.client.ResourceAccessException("test-only timeout");};
            if(mode.equals("empty"))return request->new ContractAnalysisAiPort.Result("synthetic-fault","contract-case-v1","OTHER","GENERAL",true,List.of(),List.of(),List.of());
            if(mode.equals("malformed")){
                var invalidClient=RestClient.builder().baseUrl("http://synthetic.invalid").requestInterceptor((request,body,next)->{
                    var response=new org.springframework.mock.http.client.MockClientHttpResponse("{invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8),org.springframework.http.HttpStatus.OK);
                    response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);return response;
                }).build();
                return new FastApiContractAnalysisAdapter(invalidClient);
            }
            return new FastApiContractAnalysisAdapter(RestClient.builder().baseUrl(System.getenv("AUDIT_CONTRACT_TEST_AI_URL")).build());
        }
        @Bean @Primary OcrPort testOcr(){
            var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();factory.setConnectTimeout(Duration.ofSeconds(5));factory.setReadTimeout(Duration.ofSeconds(600));
            return new FastApiOcrAdapter(RestClient.builder().baseUrl(System.getenv("AUDIT_CONTRACT_TEST_OCR_URL")).requestFactory(factory).build());
        }
        @Bean @Primary OperationAuditPort testAudit(DataSource data,ObjectMapper json){
            var jdbc=new JdbcTemplate(data);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='CONTRACT_CASE_TEST_AUDIT'",Integer.class)==0)jdbc.execute("CREATE TABLE CONTRACT_CASE_TEST_AUDIT(ID VARCHAR(36) PRIMARY KEY,EVENT_JSON CLOB NOT NULL)");
            return event->{try{String payload=json.writeValueAsString(event);jdbc.update(c->{var p=c.prepareStatement("INSERT INTO CONTRACT_CASE_TEST_AUDIT(ID,EVENT_JSON) VALUES(?,?)");p.setString(1,UUID.randomUUID().toString());p.setCharacterStream(2,new java.io.StringReader(payload),payload.length());return p;});}catch(java.io.IOException error){throw new IllegalStateException(error);}};
        }
    }
}
