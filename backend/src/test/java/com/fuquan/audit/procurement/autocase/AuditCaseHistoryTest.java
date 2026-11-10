package com.fuquan.audit.procurement.autocase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AuditCaseHistoryTest {
    final InMemoryAuditCaseRepository repo=new InMemoryAuditCaseRepository();
    final CurrentUser owner=new CurrentUser("history-owner","history-owner","测试",Set.of("AUDITOR"));
    final AuditCaseService service=new AuditCaseService(repo,mock(AuditCaseTransactions.class),mock(FileStoragePort.class),
            mock(DocumentTextPort.class),mock(MaterialFieldAiPort.class),Clock.systemUTC());
    AuditCase value(int number,String user,Instant at){return new AuditCase(new UUID(0,number),user,UUID.randomUUID(),"fp","COMPLETED",0,
            "lease",Instant.EPOCH,1,at,List.of(),null);}
    @Test void allOlderThanHundredCanBeRediscoveredWithStableTiesAndConcurrentNewerInsert(){
        var origin=Instant.parse("2026-10-27T12:00:00Z");
        var expected=new ArrayList<AuditCase>();
        for(int i=1;i<=127;i++){var c=value(i,owner.userId(),origin.minusSeconds(i/5));repo.insert(c);expected.add(c);}
        repo.insert(value(500,"another-owner",origin));
        expected.sort(Comparator.comparing(AuditCase::createdAt).reversed().thenComparing(c->c.id().toString()));
        var actual=new ArrayList<UUID>();UUID cursor=null;
        do {
            var page=service.history(cursor,17,owner);
            assertThat(page.items()).hasSizeLessThanOrEqualTo(17);
            actual.addAll(page.items().stream().map(AuditCaseHistoryEntry::id).toList());
            cursor=page.nextCursor();
            if(actual.size()==17)repo.insert(value(600,owner.userId(),origin.plusSeconds(1)));
        } while(cursor!=null);
        assertThat(actual).containsExactlyElementsOf(expected.stream().map(AuditCase::id).toList());
        assertThat(actual).doesNotHaveDuplicates();
    }
    @Test void foreignOrMissingCursorAndUnboundedSizeAreRejected(){
        var foreign=value(10,"another-owner",Instant.now());repo.insert(foreign);
        assertThatThrownBy(()->service.history(foreign.id(),20,owner)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.history(UUID.randomUUID(),20,owner)).isInstanceOf(BusinessException.class);
        for(int size:List.of(0,-1,51,Integer.MAX_VALUE))
            assertThatThrownBy(()->service.history(null,size,owner)).isInstanceOf(BusinessException.class);
    }
    @Test void systemAdminCanReadAllProcurementCasesWhileModuleUsersStayOwnerScoped(){
        var first=value(21,"owner-a",Instant.parse("2026-10-28T10:00:00Z"));
        var second=value(22,"owner-b",Instant.parse("2026-10-28T11:00:00Z"));
        repo.insert(first);repo.insert(second);
        var admin=new CurrentUser("root","root","管理员",Set.of("SYSTEM_ADMIN"));
        var procurementUser=new CurrentUser("owner-a","owner-a","采购",Set.of("PROCUREMENT_USER"));

        assertThat(service.recent(admin)).extracting(AuditCase::id).containsExactly(second.id(),first.id());
        assertThat(service.owned(second.id(),admin)).isEqualTo(second);
        assertThat(service.history(null,1,admin).items()).extracting(AuditCaseHistoryEntry::id).containsExactly(second.id());
        assertThat(service.history(second.id(),1,admin).items()).extracting(AuditCaseHistoryEntry::id).containsExactly(first.id());

        assertThat(service.recent(procurementUser)).extracting(AuditCase::id).containsExactly(first.id());
        assertThatThrownBy(()->service.owned(second.id(),procurementUser)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.history(second.id(),1,procurementUser)).isInstanceOf(BusinessException.class);
    }
}
