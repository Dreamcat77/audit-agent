package com.fuquan.audit.integration.ai.fastapi;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class MaterialAdmissionTest {
    @Test void twoSlotsQueueThirdAndInterruptionDoesNotLeakCapacity() throws Exception {
        var adapter=new FastApiMaterialFieldAdapter(RestClient.create(),2);
        var pool=Executors.newFixedThreadPool(3);var release=new CountDownLatch(1);var entered=new CountDownLatch(2);
        try {
            for(int i=0;i<2;i++)pool.submit(()->{try(var slot=adapter.admit()){entered.countDown();release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var waiting=new CountDownLatch(1);var cancelled=new CountDownLatch(1);
            var third=pool.submit(()->{waiting.countDown();try(var slot=adapter.admit()){throw new AssertionError("unbounded admission");}catch(RuntimeException ex){assertThat(Thread.currentThread().isInterrupted()).isTrue();cancelled.countDown();}});
            assertThat(waiting.await(5,TimeUnit.SECONDS)).isTrue();third.cancel(true);
            assertThat(cancelled.await(5,TimeUnit.SECONDS)).isTrue();
            release.countDown();pool.shutdown();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
            try(var slot=adapter.admit()){assertThatThrownBy(adapter::admit).isInstanceOf(IllegalStateException.class);}
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void rejectsUnboundedConfiguration(){assertThatThrownBy(()->new FastApiMaterialFieldAdapter(RestClient.create(),3)).isInstanceOf(IllegalArgumentException.class);}
}
