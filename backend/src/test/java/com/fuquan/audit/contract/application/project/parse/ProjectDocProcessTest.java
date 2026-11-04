package com.fuquan.audit.contract.application.project.parse;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.*;

public class ProjectDocProcessTest {
    private Process child(String mode) throws Exception {
        String executable=ProjectDocProcess.command(System.getProperty("java.class.path")).get(0);
        return new ProcessBuilder(executable,"-Xmx32m","-cp",System.getProperty("java.class.path"),
                ProjectDocProcessTest.class.getName(),mode).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }
    @Test void timeoutKillsHungChildEvenWhenInputPipeIsBlocked() throws Exception {
        Process child=child("hang");
        assertThatThrownBy(()->ProjectDocProcess.exchange(child,new byte[1024*1024],Duration.ofMillis(300)))
                .isInstanceOf(ProjectParseException.class).hasMessage("DOC_TIME_LIMIT");
        assertThat(child.isAlive()).isFalse();
    }
    @Test void excessiveOutputIsBoundedAndKillsTheChild() throws Exception {
        Process child=child("flood");
        assertThatThrownBy(()->ProjectDocProcess.exchange(child,new byte[0],Duration.ofSeconds(5)))
                .isInstanceOf(ProjectParseException.class).hasMessageMatching("DOC_WORKER_(OUTPUT_LIMIT|FAILED)");
        assertThat(child.isAlive()).isFalse();
    }
    @Test void nonZeroExitCannotPublishPartialDocument() throws Exception {
        Process child=child("fail");
        assertThatThrownBy(()->ProjectDocProcess.exchange(child,new byte[0],Duration.ofSeconds(5)))
                .isInstanceOf(ProjectParseException.class).hasMessage("DOC_WORKER_FAILED");
    }
    @Test void childHeapExhaustionDoesNotEscapeIntoTheApplicationJvm() throws Exception {
        Process child=child("heap");
        assertThatThrownBy(()->ProjectDocProcess.exchange(child,new byte[0],Duration.ofSeconds(5)))
                .isInstanceOf(ProjectParseException.class).hasMessage("DOC_WORKER_FAILED");
        assertThat(child.isAlive()).isFalse();
    }
    @Test @EnabledIfSystemProperty(named="audit.word97.packagedJar",matches=".+")
    void packagedBootJarLaunchesActualWordWorkerWithHeapBound() throws Exception {
        List<String> command=ProjectDocProcess.command(System.getProperty("audit.word97.packagedJar"));
        assertThat(command).contains("-Xmx128m","org.springframework.boot.loader.launch.PropertiesLauncher");
        Process child=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        byte[] source=Files.readAllBytes(Path.of(System.getProperty("audit.word97.fixture"),"真实Word97_软件采购合同.doc"));
        byte[] bytes=ProjectDocProcess.exchange(child,source,Duration.ofSeconds(15));
        var result=new com.fasterxml.jackson.databind.ObjectMapper().readValue(bytes,ProjectDocTextParser.WorkerResult.class);
        assertThat(result.error()).isNull();
        assertThat(result.document().fullText().length()).isBetween(13000,14500);
        assertThat(result.document().tables()).isNotEmpty();
    }
    public static void main(String[] args) throws Exception {
        if(args[0].equals("hang")) { Thread.sleep(30000);return; }
        if(args[0].equals("fail")) { System.exit(17);return; }
        if(args[0].equals("heap")) { System.out.write(new byte[128*1024*1024]);return; }
        byte[] buffer=new byte[8192];
        for(int i=0;i<4096;i++)System.out.write(buffer);
    }
}
