package com.fuquan.audit.integration.ai.fastapi;
import java.util.function.Supplier;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.MDC;
final class BoundedSemanticHttp {
    private final AtomicBoolean occupied=new AtomicBoolean();
    <T> T call(Supplier<T> operation,long timeoutMs) {
        if(timeoutMs<=0 || !occupied.compareAndSet(false,true)) throw safe();
        var result=new CompletableFuture<T>();
        var context=MDC.getCopyOfContextMap();
        Thread worker=new Thread(() -> {
            T value=null; Throwable failure=null;
            try { if(context!=null) MDC.setContextMap(context); value=operation.get(); }
            catch(Throwable error) { failure=error; }
            finally { MDC.clear(); occupied.set(false); }
            if(failure==null) result.complete(value);else result.completeExceptionally(failure);
        },"contract-semantic-http");
        worker.setDaemon(true);
        try { worker.start(); }
        catch(RuntimeException unavailable) { occupied.set(false);throw safe(); }
        try { return result.get(timeoutMs,TimeUnit.MILLISECONDS); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw safe(); }
        catch(TimeoutException | ExecutionException failure) { throw safe(); }
        // No cancellation claim: the occupied slot remains until the actual
        // transport exits. A timed-out response has no caller publication path.
    }
    private static IllegalStateException safe() { return new IllegalStateException("semantic HTTP capacity, deadline or transport failure"); }
}
