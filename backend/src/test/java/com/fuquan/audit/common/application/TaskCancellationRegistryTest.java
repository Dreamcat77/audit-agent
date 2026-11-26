package com.fuquan.audit.common.application;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class TaskCancellationRegistryTest {
    @Test void nestedRegistrationAndLastChildDrainPreserveRunningState()throws Exception {
        var registry=new TaskCancellationRegistry();var id=UUID.randomUUID();
        var parent=registry.register(id);
        try(var nested=registry.register(id)){assertThat(registry.isRunning(id)).isTrue();}
        assertThat(registry.isRunning(id)).isTrue();
        var enter=TaskCancellationRegistry.child();var ready=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var future=pool.submit(()->{try(var child=enter.get()){ready.countDown();release.await();}return true;});
            assertThat(ready.await(2,TimeUnit.SECONDS)).isTrue();parent.close();
            assertThat(registry.isRunning(id)).isTrue();release.countDown();future.get(2,TimeUnit.SECONDS);
            assertThat(registry.isRunning(id)).isFalse();
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void interruptsRunningAndLateChildrenWithoutAffectingAnotherTask() throws Exception {
        var registry=new TaskCancellationRegistry();var id=UUID.randomUUID();
        var childReady=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try(var parent=registry.register(id)) {
            var enter=TaskCancellationRegistry.child();
            var child=pool.submit(()->{try(var registration=enter.get()){
                childReady.countDown();try{release.await();return false;}
                catch(InterruptedException expected){return true;}
            }});
            assertThat(childReady.await(2,TimeUnit.SECONDS)).isTrue();
            registry.interrupt(id);assertThat(Thread.interrupted()).isTrue();
            assertThat(TaskCancellationRegistry.currentCancellationRequested()).isTrue();
            assertThat(child.get(2,TimeUnit.SECONDS)).isTrue();
            assertThat(pool.submit(()->{try(var registration=enter.get()){return Thread.interrupted();}}).get()).isTrue();
            assertThat(pool.submit(()->{try(var other=registry.register(UUID.randomUUID())){return Thread.interrupted();}}).get()).isFalse();
        } finally {release.countDown();pool.shutdownNow();Thread.interrupted();}
        assertThat(registry.isRunning(id)).isFalse();
        assertThat(TaskCancellationRegistry.currentCancellationRequested()).isFalse();
    }
}
