package com.fuquan.audit.common.material.transfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fuquan.audit.common.material.transfer.MaterialTransfer.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.application.OperationAuditService;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.integration.file.FileSystemStorageAdapter;
import com.fuquan.audit.integration.material.memory.InMemoryMaterialTransferRepository;
import com.fuquan.audit.security.domain.CurrentUser;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MaterialTransferServiceTest {
    @TempDir Path root;
    private final CurrentUser user=new CurrentUser("actor","test","合成用户",Set.of("AUDITOR"));
    private InMemoryMaterialTransferRepository repo;private FileStoragePort storage;private MaterialTransferService service;
    private UUID key;private byte[] bytes;private Spec spec;
    @BeforeEach void init()throws Exception {
        repo=new InMemoryMaterialTransferRepository();storage=new FileSystemStorageAdapter(root,(source,target)->Files.createLink(target,source));
        service=service(storage);key=UUID.randomUUID();bytes=new byte[CHUNK_BYTES*2+97];new Random(45).nextBytes(bytes);
        spec=spec(bytes);service.create("budget",key,spec,user);
    }
    MaterialTransferService service(FileStoragePort storage){return new MaterialTransferService(repo,new MaterialTransferTransactions(repo,mock(OperationAuditService.class)),storage,new ObjectMapper());}
    static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    static Spec spec(byte[] b)throws Exception {var hashes=new ArrayList<String>();for(int i=0;i<b.length;i+=CHUNK_BYTES)hashes.add(hash(Arrays.copyOfRange(b,i,Math.min(b.length,i+CHUNK_BYTES))));return new Spec("合成预算.pdf","application/pdf",b.length,hash(b),hashes,null,"2026",null,null);}
    void part(int i){service.part("budget",key,i,new ByteArrayInputStream(Arrays.copyOfRange(bytes,i*CHUNK_BYTES,Math.min(bytes.length,(i+1)*CHUNK_BYTES))),user);}
    @Test void outOfOrderPartsResumeAcrossServiceRestartAndDeliverExactlyOnce()throws Exception {
        part(1);part(1);part(2);
        service=service(storage);
        assertThat(service.progress(service.owned("budget",key,user)).received()).containsExactly(1,2);
        assertThatThrownBy(()->service.complete("budget",key,user,(s,input)->Map.of())).isInstanceOf(BusinessException.class);
        part(0);AtomicInteger delivered=new AtomicInteger();
        Object result=service.complete("budget",key,user,(s,input)-> {assertThat(input.readAllBytes()).isEqualTo(bytes);delivered.incrementAndGet();return Map.of("id","durable-task");});
        assertThat(result).isEqualTo(Map.of("id","durable-task"));
        assertThat(service.complete("budget",key,user,(s,input)-> {throw new AssertionError("redelivery");}).toString()).contains("durable-task");
        assertThat(delivered.get()).isEqualTo(1);assertThat(service.progress(service.owned("budget",key,user)).status()).isEqualTo("COMPLETED");
        for(var p:repo.parts(service.owned("budget",key,user).id()))assertThat(storage.inspect(new StorageKey(p.storageKey())).status()).isEqualTo(StoredFileInspection.Status.DEFINITELY_MISSING);
    }
    @Test void wrongBytesMissingPartsAndWrongWholeHashNeverReachBusinessProcessing()throws Exception {
        assertThatThrownBy(()->service.part("budget",key,0,new ByteArrayInputStream(new byte[CHUNK_BYTES]),user)).isInstanceOf(BusinessException.class);
        assertThat(service.progress(service.owned("budget",key,user)).received()).isEmpty();
        var broken=new Spec(spec.fileName(),spec.contentType(),spec.size(),"0".repeat(64),spec.partHashes(),null,"2026",null,null);
        key=UUID.randomUUID();service.create("budget",key,broken,user);part(0);part(1);part(2);
        assertThatThrownBy(()->service.complete("budget",key,user,(s,input)-> {throw new AssertionError("invalid whole file delivered");})).isInstanceOf(BusinessException.class);
        assertThat(service.owned("budget",key,user).status()).isEqualTo("OPEN");
    }
    @Test void immutableMetadataAndOwnerRoleIsolation()throws Exception {
        assertThatThrownBy(()->service.create("budget",key,new Spec("替换.pdf",spec.contentType(),spec.size(),spec.sha256(),spec.partHashes(),null,"2026",null,null),user)).isInstanceOf(BusinessException.class);
        var other=new CurrentUser("other","other","其他用户",Set.of("SYSTEM_ADMIN"));
        assertThatThrownBy(()->service.owned("budget",key,other)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.create("budget",key,spec,new CurrentUser("applicant","applicant","用户",Set.of("PROCUREMENT_APPLICANT")))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.part("budget",key,-1,InputStream.nullInputStream(),user)).isInstanceOf(BusinessException.class);
    }
    @Test void concurrentSamePartAndCompletionCannotCreateTwoDeliveries()throws Exception {
        try(var pool=new TestPool()) {
            var first=pool.executor.submit(()->part(0));var second=pool.executor.submit(()->part(0));first.get();second.get();
            assertThat(repo.parts(service.owned("budget",key,user).id())).hasSize(1);
            part(1);part(2);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
            var completing=pool.executor.submit(()->service.complete("budget",key,user,(s,input)-> {calls.incrementAndGet();entered.countDown();try{assertThat(release.await(20,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new IOException(e);}return Map.of("id","one");}));
            assertThat(entered.await(20,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->service.complete("budget",key,user,(s,input)->Map.of())).isInstanceOf(BusinessException.class);
            release.countDown();completing.get();assertThat(calls.get()).isEqualTo(1);
        }
    }
    @Test void uncertainStorageIsNotMissingAndDeliveryFailurePreservesReceivedParts()throws Exception {
        part(0);var uncertain=spy(storage);doReturn(StoredFileInspection.unknown()).when(uncertain).inspect(any());
        assertThatThrownBy(()->service(uncertain).progress(service.owned("budget",key,user))).isInstanceOf(BusinessException.class);
        part(1);part(2);
        assertThatThrownBy(()->service.complete("budget",key,user,(s,input)->{throw new IOException("uncertain delivery");})).isInstanceOf(IOException.class);
        assertThat(service.progress(service.owned("budget",key,user)).received()).hasSize(3);
    }
    @Test void expiredLeaseRecoversAndStaleTokenCannotRenewFinishOrRelease() {
        var s=service.owned("budget",key,user);var now=java.time.Instant.now();
        assertThat(repo.claim(s.id(),"crashed",now.minusSeconds(240),now.minusSeconds(120))).isTrue();
        assertThat(repo.claim(s.id(),"current",now,now.plusSeconds(120))).isTrue();
        assertThat(repo.renew(s.id(),"crashed",now.plusSeconds(500))).isFalse();
        assertThat(repo.complete(s.id(),"crashed","{}")).isFalse();repo.release(s.id(),"crashed");
        assertThat(repo.find(s.id()).orElseThrow().token()).isEqualTo("current");
        assertThat(repo.complete(s.id(),"current","{}")).isTrue();
        assertThat(repo.claim(s.id(),"late",now.plusSeconds(500),now.plusSeconds(600))).isFalse();
    }
    @Test void definitelyMissingPartIsReuploadedAndCompletionCrashReplaysOriginalBusinessIdentity()throws Exception {
        part(0);var s=service.owned("budget",key,user);storage.delete(new StorageKey(repo.parts(s.id()).get(0).storageKey()));
        assertThat(service.progress(s).received()).isEmpty();part(0);part(1);part(2);
        var uncertainTx=spy(new MaterialTransferTransactions(repo,mock(OperationAuditService.class)));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("uncertain commit")).when(uncertainTx).complete(any(),any(),any(),any());
        var uncertain=new MaterialTransferService(repo,uncertainTx,storage,new ObjectMapper());var identities=new ArrayList<UUID>();
        assertThatThrownBy(()->uncertain.complete("budget",key,user,(session,input)->{identities.add(session.operation());return Map.of("id","durable-task");})).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(service.progress(s).received()).hasSize(3);
        service.complete("budget",key,user,(session,input)->{identities.add(session.operation());return Map.of("id","durable-task");});
        assertThat(identities).containsExactly(key,key);uncertain.close();
    }
    @AfterEach void close(){service.close();}
    static class TestPool implements AutoCloseable {final ExecutorService executor=Executors.newFixedThreadPool(2);public void close(){executor.shutdownNow();}}
}
