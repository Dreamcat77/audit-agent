package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.integration.dm8.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="AUDIT_CASE_TEST_DB_CONFIRM",matches="ISOLATED_AUTOCASE2")
class Dm8AutoCaseRealIntegrationTest {
    Dm8AuditCaseRepository repository;JdbcTemplate jdbc;TransactionTemplate tx;
    final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    @BeforeEach void setup()throws Exception {
        String url=System.getenv("AUDIT_CASE_TEST_DB_URL");
        assertThat(url).isEqualTo("jdbc:dm://127.0.0.1:15288");
        var ds=new DriverManagerDataSource(url,System.getenv("AUDIT_CASE_TEST_DB_USERNAME"),System.getenv("AUDIT_CASE_TEST_DB_PASSWORD"));
        jdbc=new JdbcTemplate(ds);assertThat(jdbc.queryForObject("SELECT USER FROM DUAL",String.class)).isEqualTo("AUTOCASE_APP");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);
        var config=new org.apache.ibatis.session.Configuration();config.addMapper(AutoCaseMapper.class);factory.setConfiguration(config);
        SqlSessionFactory sessions=factory.getObject();
        repository=new Dm8AuditCaseRepository(new SqlSessionTemplate(sessions).getMapper(AutoCaseMapper.class),json);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    AuditCase value(String text) {
        var fact=new AuditCase.Fact("row","supplierName","真实数据库测试供应商","TEXT","page:1","真实数据库测试供应商","SOURCE_VERIFIED","TEST","v2");
        var doc=new AuditCase.Document(UUID.randomUUID(),"测试.docx",10,"a".repeat(64),"b".repeat(64),"EXTRACTED",
                List.of(new MaterialFieldAiPort.Segment("page:1",text)),List.of(fact),"TEXT",null,1);
        return new AuditCase(UUID.randomUUID(),"dm8-regression-owner",UUID.randomUUID(),"c".repeat(64),"COMPLETED",0,"token",Instant.now().plusSeconds(1800),0,Instant.now(),List.of(doc),null);
    }
    @Test void largeChineseClobAndFactsHaveOneAuthoritativeCopyAndReopen() {
        var original=value("中文全文与证据".repeat(15000));
        tx.executeWithoutResult(s->repository.insert(original));
        var reopened=repository.find(original.id()).orElseThrow();
        assertThat(reopened.documents()).isEqualTo(original.documents());
        String stored=jdbc.queryForObject("SELECT SNAPSHOT_JSON FROM AUDIT_CASE_FILE WHERE CASE_ID=?",String.class,original.id().toString());
        assertThatCode(()->assertThat(json.readTree(stored).path("document").path("facts")).isEmpty()).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_FACT WHERE CASE_ID=?",Integer.class,original.id().toString())).isEqualTo(1);
        assertThat(reopened.documents().get(0).segments().get(0).text().length()).isGreaterThan(32767);
    }
    @Test void transactionRollbackRestoresCaseFilesFactsAndAuditBoundary() {
        var original=value("原文");tx.executeWithoutResult(s->repository.insert(original));
        assertThatThrownBy(()->tx.executeWithoutResult(s->{
            assertThat(repository.replace(original,original.update("UNKNOWN",List.of(),null))).isTrue();
            throw new IllegalStateException("adversarial failure after multi-table write");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.find(original.id()).orElseThrow().version()).isZero();
        assertThat(repository.find(original.id()).orElseThrow().documents()).isEqualTo(original.documents());
        var rolledBack=value("未提交");tx.executeWithoutResult(s->{repository.insert(rolledBack);s.setRollbackOnly();});
        assertThat(repository.find(rolledBack.id())).isEmpty();
    }
    @Test void conflictingAndUnknownFactsRemainDistinctAfterReopen() {
        var original=value("预算上限：100元；另一版本预算上限：200元");var d=original.documents().get(0);
        var facts=List.of(
                new AuditCase.Fact("a","budgetAmount","100元","MONEY","page:1","预算上限：100元","SOURCE_VERIFIED","TEST","v2"),
                new AuditCase.Fact("b","budgetAmount","200元","MONEY","page:1","预算上限：200元","SOURCE_VERIFIED","TEST","v2"),
                new AuditCase.Fact("c","supplierName","待确认","TEXT","page:1","待确认","UNVERIFIED","TEST","v2"));
        var doc=new AuditCase.Document(d.id(),d.name(),d.size(),d.sha256(),d.storageKey(),"UNKNOWN",d.segments(),facts,d.parseMethod(),"EXTERNAL_OUTCOME_UNKNOWN",1);
        var unknown=original.update("DATA_INSUFFICIENT",List.of(doc),null);
        tx.executeWithoutResult(s->repository.insert(unknown));
        var reopened=repository.find(unknown.id()).orElseThrow();
        assertThat(reopened.status()).isEqualTo("DATA_INSUFFICIENT");
        assertThat(reopened.documents().get(0)).isEqualTo(doc);
        assertThat(CaseFactPolicy.amount(reopened.documents().get(0),"budgetAmount")).isNull();
        assertThat(CaseFactPolicy.scalar(reopened.documents().get(0),"supplierName")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_FACT WHERE CASE_ID=?",Integer.class,unknown.id().toString())).isEqualTo(3);
    }
    @Test void concurrentCasHasExactlyOneWinnerAndStaleWriterCannotDeleteFacts()throws Exception {
        var original=value("并发原文");tx.executeWithoutResult(s->repository.insert(original));
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var results=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++)results.add(pool.submit(()->{start.await();return tx.execute(s->repository.replace(original,original.update("UNKNOWN",original.documents(),null)));}));
            start.countDown();int wins=0;for(var result:results)if(result.get(15,TimeUnit.SECONDS))wins++;
            assertThat(wins).isEqualTo(1);var current=repository.find(original.id()).orElseThrow();
            assertThat(current.version()).isEqualTo(1);assertThat(current.documents().get(0).facts()).hasSize(1);
        }finally{pool.shutdownNow();}
    }
    @Test void foreignCaseFileCannotReceiveFactsOrQuoteLinesAndCorruptionFailsClosed() {
        var first=value("一");var second=value("二");tx.executeWithoutResult(s->{repository.insert(first);repository.insert(second);});
        assertThatThrownBy(()->jdbc.update("INSERT INTO AUDIT_FACT(ID,CASE_ID,FILE_ID,FACT_ORDER,FACT_TYPE,DATA_TYPE,VERIFICATION,FACT_JSON) VALUES(?,?,?,0,'x','TEXT','UNVERIFIED','{}')",
                UUID.randomUUID().toString(),first.id().toString(),second.documents().get(0).id().toString())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO AUDIT_QUOTE_LINE(ID,CASE_ID,FILE_ID,ITEM_NAME,QUANTITY,UNIT_PRICE,LINE_AMOUNT,LOCATOR) VALUES(?,?,?,'x',1,1,1,'p1')",
                UUID.randomUUID().toString(),first.id().toString(),second.documents().get(0).id().toString())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        tx.executeWithoutResult(s->{
            jdbc.update("DELETE FROM AUDIT_FACT WHERE CASE_ID=?",first.id().toString());
            assertThatThrownBy(()->repository.find(first.id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("fact manifest corrupt");s.setRollbackOnly();
        });
        assertThat(repository.find(first.id()).orElseThrow().documents().get(0).facts()).hasSize(1);
    }
}
