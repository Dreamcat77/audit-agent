package com.fuquan.audit.common.application;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Interrupts local work after cancellation is durably recorded by its owning module. */
@Component
public class TaskCancellationRegistry {
    private final ConcurrentHashMap<UUID, Group> running = new ConcurrentHashMap<>();
    private static final ThreadLocal<Group> CURRENT=new ThreadLocal<>();
    private static final class Group {
        final ConcurrentHashMap<Thread,Integer> threads=new ConcurrentHashMap<>();
        Runnable onEmpty=()->{};
        volatile boolean cancelled;
    }

    public Registration register(UUID id) {
        final Registration[] registration={null};
        running.compute(id,(key,existing)->{
            Group group=existing==null?new Group():existing;
            group.onEmpty=()->running.computeIfPresent(id,(k,value)->value==group&&group.threads.isEmpty()?null:value);
            registration[0]=enter(group);
            return group;
        });
        return registration[0];
    }

    public void interrupt(UUID id) {
        Group group=running.get(id);
        if(group!=null){group.cancelled=true;group.threads.keySet().forEach(Thread::interrupt);}
    }

    public boolean isRunning(UUID id) { var group=running.get(id);return group!=null&&!group.threads.isEmpty(); }
    /** True only after the owning module durably cancels this registered work. */
    public static boolean currentCancellationRequested() { var group=CURRENT.get();return group!=null&&group.cancelled; }
    /** Capture at submission, enter in the child. A late child sees cancellation too. */
    public static java.util.function.Supplier<Registration> child() {
        Group group=CURRENT.get();
        return ()->group==null?()->{}:enter(group);
    }
    private static Registration enter(Group group) {
        Thread thread=Thread.currentThread(); Group previous=CURRENT.get();
        group.threads.merge(thread,1,Integer::sum);CURRENT.set(group);
        if(group.cancelled)thread.interrupt();
        return ()->{group.threads.computeIfPresent(thread,(t,count)->count==1?null:count-1);
            if(previous==null)CURRENT.remove();else CURRENT.set(previous);group.onEmpty.run();};
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override void close();
    }
}
