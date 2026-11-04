package com.fuquan.audit.procurement.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import com.fuquan.audit.procurement.application.port.*;
import com.fuquan.audit.procurement.domain.*;
import com.fuquan.audit.procurement.infra.memory.InMemoryProcurementQuoteCheckRepository;

class ProcurementQuoteConcurrencyTest {
    @Test void concurrentSameContentReturnsOneResultAndReplaySkipsParsing() throws Exception {
        UUID projectId=UUID.randomUUID();
        var port=mock(ProcurementProjectQueryPort.class);
        when(port.findById(projectId)).thenReturn(Optional.of(new ProcurementProject(projectId,"P","项目","S","科目",new BigDecimal("100"),null,"DRAFT",Instant.now())));
        var repository=new InMemoryProcurementQuoteCheckRepository(port);
        var barrier=new CyclicBarrier(2); var calls=new java.util.concurrent.atomic.AtomicInteger();
        ProcurementQuoteSpreadsheetPort spreadsheet=input->{
            calls.incrementAndGet();
            try {barrier.await(5,TimeUnit.SECONDS);} catch(Exception ex){throw new IOException(ex);}
            return new ProcurementQuoteSpreadsheetPort.ParsedSheet(null,List.of(new ProcurementQuoteSpreadsheetPort.QuoteRow(2,"甲",new BigDecimal("90"),"设备")));
        };
        var service=new ProcurementQuoteCheckService(spreadsheet,repository,port);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<ProcurementQuoteCheck> action=()->service.upload(projectId.toString(),new ByteArrayInputStream(new byte[]{1,2,3}));
            var a=pool.submit(action); var b=pool.submit(action);
            var first=a.get(10,TimeUnit.SECONDS); var second=b.get(10,TimeUnit.SECONDS);
            assertThat(first.id()).isEqualTo(second.id());
            assertThat(service.upload(projectId.toString(),new ByteArrayInputStream(new byte[]{1,2,3}))).isEqualTo(first);
            assertThat(calls.get()).isEqualTo(2);
            assertThat(repository.findById(first.id())).isPresent();
        } finally {pool.shutdownNow();}
    }
    @Test void thirdOperationFailsFastAndCapacityReturnsAfterParseFailure() throws Exception {
        UUID id=UUID.randomUUID(); var port=mock(ProcurementProjectQueryPort.class);
        when(port.findById(id)).thenReturn(Optional.of(new ProcurementProject(id,"P","项目","S","科目",new BigDecimal("100"),null,"DRAFT",Instant.now())));
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        ProcurementQuoteSpreadsheetPort parser=input->{entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException();}catch(InterruptedException ex){Thread.currentThread().interrupt();}throw new IOException("bad data");};
        var service=new ProcurementQuoteCheckService(parser,new InMemoryProcurementQuoteCheckRepository(port),port);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> action=()->{try{service.upload(id.toString(),new ByteArrayInputStream(new byte[]{1}));return false;}catch(IOException ex){return true;}};
            var a=pool.submit(action);var b=pool.submit(action);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->service.upload(id.toString(),new ByteArrayInputStream(new byte[]{2}))).hasMessageContaining("繁忙");
            release.countDown();assertThat(a.get(5,TimeUnit.SECONDS)).isTrue();assertThat(b.get(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->service.upload(id.toString(),new ByteArrayInputStream(new byte[]{3}))).isInstanceOf(IOException.class);
        }finally{release.countDown();pool.shutdownNow();}
    }
}
