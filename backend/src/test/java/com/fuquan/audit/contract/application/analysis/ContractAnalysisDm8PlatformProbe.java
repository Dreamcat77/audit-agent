package com.fuquan.audit.contract.application.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fuquan.audit.integration.dm8.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.mybatis.spring.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.fuquan.audit.contract.application.analysis.ContractAnalysisCase.*;

/** Executable adversarial check; only the dedicated disposable AUTOCASE2 schema is allowed. */
public class ContractAnalysisDm8PlatformProbe {
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        require("ISOLATED_AUTOCASE2_CONTRACT".equals(System.getenv("AUDIT_CONTRACT_TEST_DB_CONFIRM")),"isolated confirmation required");
        require("jdbc:dm://127.0.0.1:15288".equals(System.getenv("AUDIT_CONTRACT_TEST_DB_URL")),"isolated proxy required");
        require("CONTRACT_CASE_QA".equals(System.getenv("AUDIT_CONTRACT_TEST_DB_USERNAME")),"dedicated schema required");
        var options=new com.zaxxer.hikari.HikariConfig();
        options.setJdbcUrl(System.getenv("AUDIT_CONTRACT_TEST_DB_URL"));options.setUsername(System.getenv("AUDIT_CONTRACT_TEST_DB_USERNAME"));options.setPassword(System.getenv("AUDIT_CONTRACT_TEST_DB_PASSWORD"));
        options.setMaximumPoolSize(8);options.setMinimumIdle(0);options.setConnectionTimeout(20000);
        try(var data=new com.zaxxer.hikari.HikariDataSource(options)){
        // Warm the same bounded pool used by production; the test TCP bridge's handshake
        // backlog must not be confused with DM8 compare-and-set correctness.
        var warm=new ArrayList<java.sql.Connection>();
        try{for(int i=0;i<8;i++)warm.add(data.getConnection());}finally{for(var connection:warm)connection.close();}
        var jdbc=new JdbcTemplate(data);
        require("CONTRACT_CASE_QA".equals(jdbc.queryForObject("SELECT USER FROM DUAL",String.class)),"database identity mismatch");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(data);
        var config=new org.apache.ibatis.session.Configuration();config.addMapper(ContractAnalysisMapper.class);factory.setConfiguration(config);
        var session=new SqlSessionTemplate(factory.getObject());var mapper=session.getMapper(ContractAnalysisMapper.class);
        var repository=new Dm8ContractAnalysisRepository(mapper,new ObjectMapper().registerModule(new JavaTimeModule()));
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(data));
        Instant now=Instant.now();UUID project=UUID.randomUUID();String owner="SYNTHETIC_CONTRACT_PLATFORM_PROBE";
        var original=new ContractAnalysisCase(identity(owner,project),project,owner,sha256(project.toString()),State.QUEUED,0,null,null,0,now,now.plusSeconds(1800),List.of(),List.of(),null,1);
        transaction.executeWithoutResult(s->repository.insert(original));
        var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(8);var futures=new ArrayList<Future<Boolean>>();
        try {
            for(int i=0;i<8;i++)futures.add(executor.submit(()->{start.await();return transaction.execute(s->repository.replace(original,original.claim(UUID.randomUUID().toString(),now),now));}));
            start.countDown();int claims=0;for(var future:futures)if(future.get(30,TimeUnit.SECONDS))claims++;
            require(claims==1,"DM8 concurrent claim was not exclusive");
        } finally {executor.shutdownNow();}
        var first=repository.find(original.id()).orElseThrow();Instant late=now.plusSeconds(241);
        require(!transaction.execute(s->repository.replace(first,first.release(State.UNKNOWN,List.of(),List.of(),"LATE"),late)),"expired writer published");
        var recovered=first.claim("recovered-platform-probe",late);
        require(transaction.execute(s->repository.replace(first,recovered,late)),"lease recovery failed");
        require(!transaction.execute(s->repository.replace(first,first.release(State.UNKNOWN,List.of(),List.of(),"STALE"),late)),"replaced writer published");
        var terminal=recovered.release(State.UNKNOWN,List.of(),List.of(),"SYNTHETIC_PLATFORM_PROBE_FINISHED");
        require(transaction.execute(s->repository.replace(recovered,terminal,late.plusSeconds(1))),"terminal checkpoint failed");
        var reopened=new Dm8ContractAnalysisRepository(new SqlSessionTemplate(factory.getObject()).getMapper(ContractAnalysisMapper.class),new ObjectMapper().registerModule(new JavaTimeModule()));
        require(reopened.find(terminal.id()).orElseThrow().equals(terminal),"fresh repository did not reopen exact snapshot");
        require(!reopened.pending(late.plusSeconds(1000),200).contains(terminal.id()),"UNKNOWN was scheduled for retry");
        UUID otherProject=UUID.randomUUID();var rollback=new ContractAnalysisCase(identity(owner,otherProject),otherProject,owner,sha256(otherProject.toString()),State.QUEUED,0,null,null,0,now,now.plusSeconds(1800),List.of(),List.of(),null,1);
        try{transaction.executeWithoutResult(s->{repository.insert(rollback);throw new IllegalStateException("SYNTHETIC_ROLLBACK");});}catch(IllegalStateException expected){require(expected.getMessage().equals("SYNTHETIC_ROLLBACK"),"unexpected transaction failure");}
        require(reopened.find(rollback.id()).isEmpty(),"rolled back insertion remained durable");
        System.out.println("DM8_CONTRACT_PLATFORM=PASS exclusive_claims=1 contenders=8 expired_writer_rejected=true stale_writer_rejected=true fresh_repository_reopened=true unknown_not_rescheduled=true rollback_preserved=true original_database_modified=false");
        }
    }
}
