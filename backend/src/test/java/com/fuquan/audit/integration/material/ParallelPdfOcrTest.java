package com.fuquan.audit.integration.material;

import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.common.material.application.port.DocumentTextPort;
import com.fuquan.audit.review.application.port.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.Test;

class ParallelPdfOcrTest {
    byte[] pdf(int pages)throws Exception{return new PdfReadCheckpointTest().pdf(pages);}
    int number(OcrRequest request) {
        try(var input=request.source().openStream();var d=PDDocument.load(input)){return d.getPage(0).getCOSObject().getInt("TEST_PAGE");}
        catch(IOException e){throw new UncheckedIOException(e);}
    }
    OcrResult result(OcrRequest r,int n){String text="第"+n+"页金额"+n+"元";return new OcrResult(r.documentId(),text,.99,List.of(new OcrPage(1,text,.99)),false,"TEST",1);}
    @Test void outOfOrderWorkersKeepEvidenceAndCheckpointsInPageOrderAndResume()throws Exception {
        var fourth=new CountDownLatch(1);var calls=ConcurrentHashMap.<Integer>newKeySet();var checkpoints=new ArrayList<DocumentTextPort.PdfCheckpoint>();
        var reader=new OfficeDocumentTextReader(in->new byte[0],r->{int n=number(r);calls.add(n);
            if(n==4)fourth.countDown();if(n==1)try{assertThat(fourth.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new AssertionError(e);}
            return result(r,n);},2);
        try {
            byte[] bytes=pdf(162);var read=reader.read(new ByteArrayInputStream(bytes),"x.pdf","d",10000000,512,768000,null,checkpoints::add);
            assertThat(calls).hasSize(162);assertThat(read.segments()).hasSize(162);assertThat(read.coverageGaps()).isEmpty();
            for(int n=1;n<=162;n++)assertThat(read.segments().get(n-1).locator()).contains("pdf/page:"+n+"/");
            var half=checkpoints.stream().filter(c->c.completedPages().size()==80).findFirst().orElseThrow();calls.clear();
            var resumed=reader.read(new ByteArrayInputStream(bytes),"x.pdf","d",10000000,512,768000,half,c->{});
            assertThat(calls).hasSize(82).noneMatch(half.completedPages()::contains);assertThat(resumed).isEqualTo(read);
        }finally{reader.close();}
    }
    @Test void globalSlotsAreSharedByDifferentDocumentsAndReleasedAfterFailure()throws Exception {
        var active=new AtomicInteger();var maximum=new AtomicInteger();var firstTwo=new CountDownLatch(2);var release=new CountDownLatch(1);
        var reader=new OfficeDocumentTextReader(in->new byte[0],r->{int n=number(r);int a=active.incrementAndGet();maximum.accumulateAndGet(a,Math::max);firstTwo.countDown();
            try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("gate");return result(r,n);}
            catch(InterruptedException e){throw new AssertionError(e);}finally{active.decrementAndGet();}},2);
        var threads=Executors.newFixedThreadPool(2);byte[] bytes=pdf(4);
        try {
            var one=threads.submit(()->reader.read(new ByteArrayInputStream(bytes),"x.pdf","one",1000000,20,20000));
            var two=threads.submit(()->reader.read(new ByteArrayInputStream(bytes),"x.pdf","two",1000000,20,20000));
            assertThat(firstTwo.await(5,TimeUnit.SECONDS)).isTrue();assertThat(maximum.get()).isEqualTo(2);release.countDown();
            assertThat(one.get(10,TimeUnit.SECONDS).segments()).hasSize(4);assertThat(two.get(10,TimeUnit.SECONDS).segments()).hasSize(4);
            assertThat(maximum.get()).isEqualTo(2);assertThat(active.get()).isZero();
        }finally{release.countDown();threads.shutdownNow();reader.close();}
    }
    @Test void lostCheckpointFenceStopsDispatchAndDrainsAllAlreadySubmittedOwners()throws Exception {
        var calls=new AtomicInteger();var active=new AtomicInteger();var reader=new OfficeDocumentTextReader(in->new byte[0],r->{calls.incrementAndGet();active.incrementAndGet();try{return result(r,number(r));}finally{active.decrementAndGet();}},2);
        var lost=new IllegalStateException("lost durable lease");byte[] bytes=pdf(20);
        try {
            assertThatThrownBy(()->reader.read(new ByteArrayInputStream(bytes),"x.pdf","d",1000000,30,20000,null,c->{if(!c.completedPages().isEmpty())throw lost;})).isSameAs(lost);
            assertThat(calls.get()).isEqualTo(2);assertThat(active.get()).isZero();
        }finally{reader.close();}
    }
    @Test void interruptCannotReturnWhileAnotherWorkerStillReadsItsTemporaryInput()throws Exception {
        var secondStarted=new CountDownLatch(1);var release=new CountDownLatch(1);var aliveInput=new AtomicBoolean();var failure=new AtomicReference<Throwable>();
        var reader=new OfficeDocumentTextReader(in->new byte[0],r->{int n=number(r);
            if(n==2){secondStarted.countDown();try{assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();aliveInput.set(number(r)==2);}catch(InterruptedException e){throw new AssertionError(e);}}
            return result(r,n);},2);byte[] bytes=pdf(4);
        var owner=new Thread(()->{try{reader.read(new ByteArrayInputStream(bytes),"x.pdf","d",1000000,10,20000);}catch(Throwable t){failure.set(t);}});
        try {
            owner.start();assertThat(secondStarted.await(5,TimeUnit.SECONDS)).isTrue();owner.interrupt();
            owner.join(100);assertThat(owner.isAlive()).isTrue();release.countDown();owner.join(5000);
            assertThat(owner.isAlive()).isFalse();assertThat(aliveInput).isTrue();assertThat(failure.get()).isInstanceOf(DocumentTextPort.ReadFailure.class);
        }finally{release.countDown();owner.join(5000);reader.close();}
    }
    @Test void oneEmptyScanDoesNotDiscardOtherCompletedPages()throws Exception {
        var reader=new OfficeDocumentTextReader(in->new byte[0],r->{int n=number(r);return n==2?new OcrResult(r.documentId(),"",.99,List.of(new OcrPage(1,"",.99)),false,"TEST",1):result(r,n);},2);
        try {var read=reader.read(new ByteArrayInputStream(pdf(4)),"x.pdf","d",1000000,10,20000);
            assertThat(read.segments()).hasSize(3);assertThat(read.coverageGaps()).singleElement().asString().contains("pdf/page:2/");
        }finally{reader.close();}
    }
}
