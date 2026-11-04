import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.integration.dm8.*;
import com.fuquan.audit.procurement.autocase.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;

/** Real isolated DM8 history query regression. All synthetic fixtures roll back. No DDL. */
public final class Qa26HistoryPlatformProbe {
    public static void main(String[] args)throws Exception {
        String url=System.getenv("AUDIT_DB_URL"),user=System.getenv("AUDIT_DB_USERNAME");
        if(!"QA26_FULL2".equals(user)||url==null||!url.equals("jdbc:dm://127.0.0.1:15287"))throw new IllegalStateException("ISOLATED_DATABASE_REQUIRED");
        var ds=new UnpooledDataSource("dm.jdbc.driver.DmDriver",url,user,System.getenv("AUDIT_DB_PASSWORD"));
        var cfg=new Configuration(new Environment("qa26",new JdbcTransactionFactory(),ds));cfg.addMapper(AutoCaseMapper.class);
        var json=new ObjectMapper().findAndRegisterModules();
        try(var session=new SqlSessionFactoryBuilder().build(cfg).openSession(false)) {
            var mapper=session.getMapper(AutoCaseMapper.class);var repo=new Dm8AuditCaseRepository(mapper,json);
            var at=Instant.parse("2000-01-01T00:00:00.123456789Z");
            var owner="qa26-history-probe-"+UUID.randomUUID();var expected=new ArrayList<UUID>();
            for(int i=1;i<=131;i++) {
                var id=new UUID(0,i);expected.add(id);
                var value=new AuditCase(id,owner,UUID.randomUUID(),"a".repeat(64),"DATA_INSUFFICIENT",0,"test-token",Instant.EPOCH,0,at.minusSeconds(i/5),List.of(),null);
                mapper.insert(new AutoCaseMapper.Row(id.toString(),owner,value.operationKey().toString(),value.fingerprint(),value.status(),0,value.leaseToken(),
                        LocalDateTime.ofInstant(value.leaseUntil(),ZoneOffset.UTC),LocalDateTime.ofInstant(value.createdAt(),ZoneOffset.UTC),json.writeValueAsString(value)));
            }
            var otherOwner="qa26-history-other-"+UUID.randomUUID();var otherId=UUID.randomUUID();
            var other=new AuditCase(otherId,otherOwner,UUID.randomUUID(),"b".repeat(64),"DATA_INSUFFICIENT",0,"test-token",Instant.EPOCH,0,at,List.of(),null);
            mapper.insert(new AutoCaseMapper.Row(otherId.toString(),otherOwner,other.operationKey().toString(),other.fingerprint(),other.status(),0,other.leaseToken(),
                    LocalDateTime.ofInstant(Instant.EPOCH,ZoneOffset.UTC),LocalDateTime.ofInstant(at,ZoneOffset.UTC),json.writeValueAsString(other)));
            var actual=new ArrayList<UUID>();UUID cursor=null;
            do {var page=repo.history(owner,cursor,17);actual.addAll(page.stream().map(AuditCaseHistoryEntry::id).toList());
                if(page.size()<17)break;cursor=page.get(page.size()-1).id();
                if(actual.size()>150)throw new IllegalStateException("PAGINATION_LOOP");
            }while(true);
            if(!actual.equals(expected))throw new IllegalStateException("PAGINATION_GAP_DUPLICATE_OR_ORDER");
            if(!repo.history(otherOwner,null,17).stream().map(AuditCaseHistoryEntry::id).toList().equals(List.of(otherId)))throw new IllegalStateException("OWNER_LEAK");
            if(!repo.history(owner,otherId,17).isEmpty())throw new IllegalStateException("FOREIGN_CURSOR_LEAK");
            session.rollback(true);
            if(mapper.find(expected.get(0).toString())!=null)throw new IllegalStateException("FIXTURE_ROLLBACK_FAILED");
            System.out.println("QA26_HISTORY_DM8=PASS rows=131 duplicate_timestamps=true timestamp_precision=true owner_isolation=true fixtures_rolled_back=true ddl=false");
        }
    }
}
