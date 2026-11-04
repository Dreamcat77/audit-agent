package com.fuquan.audit.contract.application.project.parse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Child JVM is a resource boundary: thread interruption cannot stop a hostile POI constructor. */
final class ProjectDocProcess {
    static final int OUTPUT_LIMIT=16*1024*1024;
    private static final Semaphore CAPACITY=new Semaphore(2);
    private ProjectDocProcess() { }

    static byte[] parse(byte[] bytes) {
        if(!CAPACITY.tryAcquire())throw new ProjectParseException("DOC_CAPACITY_LIMIT");
        try {
            Process child=new ProcessBuilder(command(System.getProperty("java.class.path")))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return exchange(child,bytes,Duration.ofSeconds(15));
        } catch(IOException exception) { throw new ProjectParseException("DOC_WORKER_UNAVAILABLE",exception); }
        finally { CAPACITY.release(); }
    }
    static List<String> command(String classpath) {
        Path javaExecutable=Path.of(System.getProperty("java.home"),"bin","java");
        if(!Files.isExecutable(javaExecutable))javaExecutable=Path.of(System.getProperty("java.home"),"bin","java.exe");
        List<String> command=new ArrayList<>(List.of(javaExecutable.toString(),"-Xmx128m","-XX:+ExitOnOutOfMemoryError",
                "-Djava.awt.headless=true","-cp",classpath));
        // A Boot executable jar exposes its nested classes through the standard PropertiesLauncher.
        if(!classpath.contains(File.pathSeparator) && classpath.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            command.add("-Dloader.main="+ProjectDocTextParser.class.getName());
            command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
        } else command.add(ProjectDocTextParser.class.getName());
        return List.copyOf(command);
    }
    static byte[] exchange(Process child,byte[] bytes,Duration timeout) {
        var writer=CompletableFuture.runAsync(()->{
            try(var input=child.getOutputStream()) { input.write(bytes); }
            catch(IOException exception) { throw new java.io.UncheckedIOException(exception); }
        });
        var reader=CompletableFuture.supplyAsync(()->{
            try(var output=child.getInputStream();var result=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int total=0;
                for(int read;(read=output.read(buffer))!=-1;) {
                    total+=read;
                    if(total>OUTPUT_LIMIT) {
                        child.destroyForcibly();
                        throw new ProjectParseException("DOC_WORKER_OUTPUT_LIMIT");
                    }
                    result.write(buffer,0,read);
                }
                return result.toByteArray();
            } catch(IOException exception) { throw new java.io.UncheckedIOException(exception); }
        });
        long deadline=System.nanoTime()+timeout.toNanos();
        try {
            if(!child.waitFor(timeout.toNanos(),TimeUnit.NANOSECONDS))throw new ProjectParseException("DOC_TIME_LIMIT");
            if(child.exitValue()!=0)throw new ProjectParseException("DOC_WORKER_FAILED");
            writer.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            return reader.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
        } catch(InterruptedException exception) {
            Thread.currentThread().interrupt();throw new ProjectParseException("DOC_INTERRUPTED");
        } catch(TimeoutException exception) { throw new ProjectParseException("DOC_TIME_LIMIT"); }
        catch(ExecutionException exception) {
            if(exception.getCause() instanceof ProjectParseException parse)throw parse;
            throw new ProjectParseException("DOC_WORKER_FAILED");
        } finally {
            if(child.isAlive()) {
                child.destroyForcibly();
                try { child.waitFor(1,TimeUnit.SECONDS); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
        }
    }
}
