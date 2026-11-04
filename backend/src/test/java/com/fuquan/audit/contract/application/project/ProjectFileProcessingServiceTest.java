package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.common.observability.*;
import com.fuquan.audit.contract.application.port.*;
import com.fuquan.audit.contract.application.project.archive.*;
import com.fuquan.audit.contract.application.project.extraction.*;
import com.fuquan.audit.contract.application.project.parse.*;
import com.fuquan.audit.contract.domain.project.*;
import com.fuquan.audit.contract.infra.memory.project.*;
import com.fuquan.audit.integration.file.*;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.review.application.port.*;
import com.fuquan.audit.security.application.port.UserAccountRoleQueryPort;
import com.fuquan.audit.security.domain.*;
import java.io.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectFileProcessingServiceTest {
    @TempDir Path root;

    @Test void zipAcceptanceAndExpansionNeverInvokeChildParsersAndEachChildHasItsOwnReopenableJob() throws Exception {
        var f=new Fixture();
        byte[] bytes;
        try(var output=new ByteArrayOutputStream();var zip=new ZipOutputStream(output)) {
            for(String name:List.of("合同.docx","附件.docx")) {
                zip.putNextEntry(new ZipEntry(name));zip.write(docx());zip.closeEntry();
            }
            zip.finish();bytes=output.toByteArray();
        }
        var accepted=f.accept("package.zip","application/zip",bytes,"zip-original");
        assertThat(f.parserCalls).hasValue(0);
        var parent=f.jobs.byFile(f.project.id(),accepted.id()).orElseThrow();
        assertThat(f.processing.process(parent.id(),f.actor,token->{}).status()).isEqualTo("SUCCEEDED");
        assertThat(f.parserCalls).hasValue(0);
        var children=f.repositories.projectFileRepository().findByProjectId(f.project.id()).stream()
                .filter(file->accepted.id().equals(file.parentArchiveFileId())).toList();
        assertThat(children).hasSize(2);
        assertThat(f.jobs.byProject(f.project.id())).hasSize(3);
        for(var child:children) {
            assertThat(child.parseStatus()).isEqualTo(ProjectFileParseStatus.PENDING);
            var childJob=f.jobs.byFile(f.project.id(),child.id()).orElseThrow();
            assertThat(childJob.id()).isNotEqualTo(parent.id());
            assertThat(f.processing.process(childJob.id(),f.actor,token->{}).status()).isEqualTo("SUCCEEDED");
            assertThat(f.parses.findLatestByFileId(child.id())).isPresent();
        }
        assertThat(f.parserCalls).hasValue(2);
        assertThat(f.processing.process(parent.id(),f.actor,token->{}).status()).isEqualTo("SUCCEEDED");
        assertThat(f.jobs.byProject(f.project.id())).hasSize(3);
    }

    @Test void busyOcrIsDeferredUnderSameDurableIdentityAndNeverBecomesFailed() throws Exception {
        var f=new Fixture();
        f.parserBehavior=file->{throw new OcrCapacityUnavailableException();};
        var file=f.accept("合同.docx",DOCX,docx(),"busy-original");
        var job=f.jobs.byFile(f.project.id(),file.id()).orElseThrow();
        var deferred=f.processing.process(job.id(),f.actor,token->{});
        assertThat(deferred.status()).isEqualTo("QUEUED");
        assertThat(deferred.reasonCode()).isEqualTo("OCR_CAPACITY_BUSY");
        assertThat(f.jobs.attempts(job.id())).extracting(ProjectFileJob.Attempt::status).containsExactly("DEFERRED");
        assertThat(f.parses.findLatestByFileId(file.id())).isEmpty();
        f.parserBehavior=ignored->parsed();
        assertThat(f.processing.process(job.id(),f.actor,token->{}).status()).isEqualTo("SUCCEEDED");
        assertThat(f.jobs.byProject(f.project.id())).hasSize(1);
    }

    @Test void unknownStorageIsNotMissingAndCannotBeRetriedWithAReplacementIdentity() throws Exception {
        var f=new Fixture();var file=f.accept("合同.docx",DOCX,docx(),"unknown-original");
        doReturn(StoredFileInspection.unknown()).when(f.storage).inspect(new StorageKey(file.storageKey()));
        var job=f.jobs.byFile(f.project.id(),file.id()).orElseThrow();
        assertThat(f.processing.process(job.id(),f.actor,token->{}).status()).isEqualTo("UNKNOWN");
        assertThat(f.jobs.retry(f.project.id(),job.id(),f.clock.instant())).isFalse();
        assertThat(f.parserCalls).hasValue(0);
        assertThat(f.parses.findLatestByFileId(file.id())).isEmpty();
    }

    @Test void explicitFailedRetryIsAuditedAndRetainsTheOriginalFileAndJob() throws Exception {
        var f=new Fixture();var file=f.accept("合同.docx",DOCX,docx(),"retry-original");
        f.parserBehavior=ignored->{throw new ProjectParseException("SYNTHETIC_PARSE_FAILURE");};
        var job=f.jobs.byFile(f.project.id(),file.id()).orElseThrow();
        assertThat(f.processing.process(job.id(),f.actor,token->{}).status()).isEqualTo("FAILED");
        var retried=f.processing.retry(f.project.id(),file.id(),f.actor,new ProjectRequestMetadata("retry-request","retry-trace","127.0.0.1"));
        assertThat(retried.id()).isEqualTo(job.id());assertThat(retried.fileId()).isEqualTo(file.id());
        assertThat(retried.status()).isEqualTo("QUEUED");
        assertThat(f.repositories.projectFileRepository().findByProjectIdAndId(f.project.id(),file.id()).orElseThrow().parseStatus())
                .isEqualTo(ProjectFileParseStatus.PENDING);
        assertThat(f.audits).anySatisfy(event->{
            assertThat(event.eventType()).isEqualTo("PROJECT_FILE_PROCESSING_RETRIED");
            assertThat(event.resourceId()).isEqualTo(file.id().toString());
            assertThat(event.requestId()).isEqualTo("retry-request");assertThat(event.taskId()).isNull();
        });
        assertThatThrownBy(()->f.processing.retry(f.project.id(),file.id(),f.actor,
                new ProjectRequestMetadata("retry-request-2","retry-trace-2","127.0.0.1"))).isInstanceOf(BusinessException.class);
        f.parserBehavior=ignored->parsed();
        assertThat(f.processing.process(job.id(),f.actor,token->{}).status()).isEqualTo("SUCCEEDED");
        assertThat(f.jobs.byProject(f.project.id())).hasSize(1);
    }

    @Test void timedOutOcrCannotPublishAPermanentFailedParseWhenTheRemoteOutcomeIsUnknown() throws Exception {
        var f=new Fixture();
        f.ocr=request->{throw new ExternalServiceException(ExternalServiceType.OCR,new java.net.SocketTimeoutException());};
        // Fixture's forwarding OCR port observes the changed delegate, while storage stays real.
        var output=new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",output);
        var file=f.accept("扫描合同.png","image/png",output.toByteArray(),"ocr-timeout-original");
        var job=f.jobs.byFile(f.project.id(),file.id()).orElseThrow();
        assertThat(f.processing.process(job.id(),f.actor,token->{}).status()).isEqualTo("UNKNOWN");
        assertThat(f.repositories.projectFileRepository().findByProjectIdAndId(f.project.id(),file.id()).orElseThrow()
                .parseStatus()).isEqualTo(ProjectFileParseStatus.PENDING);
        assertThat(f.parses.findLatestByFileId(file.id())).isEmpty();
    }

    @Test void aLateLeaseOwnerCannotPublishAnyParseOverAReclaimedAttempt() throws Exception {
        var f=new Fixture();var file=f.accept("合同.docx",DOCX,docx(),"late-original");
        var job=f.jobs.byFile(f.project.id(),file.id()).orElseThrow();
        f.parserBehavior=ignored->{
            f.clock.current=f.clock.current.plusSeconds(901);
            assertThat(f.jobs.claim(job.id(),"new-owner",f.clock.instant(),f.clock.instant().plusSeconds(900))).isTrue();
            return parsed();
        };
        assertThatThrownBy(()->f.processing.process(job.id(),f.actor,token->{})).isInstanceOf(RuntimeException.class);
        assertThat(f.parses.findLatestByFileId(file.id())).isEmpty();
        assertThat(f.jobs.find(job.id()).orElseThrow().leaseToken()).isEqualTo("new-owner");
        assertThat(f.repositories.projectFileRepository().findByProjectIdAndId(f.project.id(),file.id()).orElseThrow()
                .parseStatus()).isEqualTo(ProjectFileParseStatus.PENDING);
    }

    @Test void anUnavailableActorIsDurablyBlockedWithoutInventingAnAuthorizedUserOrStallingTheQueue() throws Exception {
        var f=new Fixture();var file=f.accept("合同.docx",DOCX,docx(),"actor-unavailable-original");
        var users=mock(UserAccountRoleQueryPort.class);
        when(users.findActiveById(f.actor.userId())).thenReturn(Optional.empty());
        var job=f.jobs.byFile(f.project.id(),file.id()).orElseThrow();
        try(var worker=new WorkerResource(new ProjectFileProcessingWorker(f.jobs,f.processing,users,f.clock))) {
            worker.worker.scan();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while("QUEUED".equals(f.jobs.find(job.id()).orElseThrow().status()) && System.nanoTime()<deadline) Thread.sleep(10);
            assertThat(f.jobs.find(job.id()).orElseThrow().status()).isEqualTo("UNKNOWN");
            assertThat(f.jobs.find(job.id()).orElseThrow().reasonCode()).isEqualTo("FILE_OWNER_UNAVAILABLE");
            assertThat(f.jobs.pending(f.clock.instant(),1)).isEmpty();
            assertThat(f.audits).anySatisfy(event->{
                assertThat(event.eventType()).isEqualTo("PROJECT_FILE_PROCESSING_BLOCKED");
                assertThat(event.userId()).isEqualTo(f.actor.userId());
                assertThat(event.taskId()).isNull();
            });
        }
        assertThat(f.parserCalls).hasValue(0);
    }

    @Test void automaticAdvancementResumesFromPublishedParseUnderSameJobAfterRestart() throws Exception {
        var f=new Fixture();
        doReturn(true).when(f.ingestion).automaticProgressionEnabled();
        var file=f.accept("合同.docx",DOCX,docx(),"automatic-original");
        var job=f.jobs.byFile(f.project.id(),file.id()).orElseThrow();
        var pending=f.processing.process(job.id(),f.actor,token->{});
        assertThat(pending.status()).isEqualTo("QUEUED");
        assertThat(pending.reasonCode()).isEqualTo("AUTO_PROGRESSION_PENDING");
        UUID parse=f.parses.findLatestByFileId(file.id()).orElseThrow().parseResultId();
        assertThat(pending.parseResultId()).isEqualTo(parse);
        // Simulate a crash after acquiring the second-stage lease.
        assertThat(f.jobs.claim(job.id(),"interrupted-owner",f.clock.instant(),f.clock.instant().plusSeconds(900))).isTrue();
        f.clock.current=f.clock.current.plusSeconds(901);
        var resumed=f.processing.process(job.id(),f.actor,token->{});
        assertThat(resumed.status()).isEqualTo("SUCCEEDED");
        assertThat(resumed.id()).isEqualTo(job.id());
        assertThat(resumed.parseResultId()).isEqualTo(parse);
        assertThat(f.parserCalls).hasValue(1);
        assertThat(f.parses.findLatestByFileId(file.id()).orElseThrow().parseResultId()).isEqualTo(parse);
        verify(f.ingestion).advance(eq(f.project.id()),any(),eq(f.actor));
    }

    @Test void automaticAdvancementCannotFinishUsingAnExpiredLease() throws Exception {
        var f=new Fixture();
        doReturn(true).when(f.ingestion).automaticProgressionEnabled();
        var file=f.accept("合同.docx",DOCX,docx(),"automatic-late-owner");
        var job=f.jobs.byFile(f.project.id(),file.id()).orElseThrow();
        f.processing.process(job.id(),f.actor,token->{});
        doAnswer(call->{
            f.clock.current=f.clock.current.plusSeconds(901);
            assertThat(f.jobs.claim(job.id(),"new-owner",f.clock.instant(),f.clock.instant().plusSeconds(900))).isTrue();
            return null;
        }).when(f.ingestion).advance(eq(f.project.id()),any(),eq(f.actor));
        f.processing.process(job.id(),f.actor,token->{});
        assertThat(f.jobs.find(job.id()).orElseThrow().status()).isEqualTo("PROCESSING");
        assertThat(f.jobs.find(job.id()).orElseThrow().leaseToken()).isEqualTo("new-owner");
        assertThat(f.parserCalls).hasValue(1);
    }

    private static final String DOCX="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static ParsedProjectDocument parsed() {return new ParsedProjectDocument(List.of(
            new ParsedProjectDocument.Segment("docx/body/paragraph:1","Synthetic contract amount: 42.50")));}
    private static byte[] docx() throws Exception {
        try(var document=new XWPFDocument();var output=new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Synthetic contract amount: 42.50");document.write(output);return output.toByteArray();
        }
    }
    private static class MutableClock extends Clock {
        Instant current=Instant.parse("2026-10-14T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId ignored){return this;}public Instant instant(){return current;}
    }
    private record WorkerResource(ProjectFileProcessingWorker worker) implements AutoCloseable {public void close(){worker.close();}}
    private class Fixture {
        final MutableClock clock=new MutableClock();
        final InMemoryProjectRepositories repositories=new InMemoryProjectRepositories();
        final InMemoryProjectFileParseResultRepository parses=new InMemoryProjectFileParseResultRepository();
        final InMemoryProjectFileJobRepository jobs=new InMemoryProjectFileJobRepository();
        final CurrentUser actor=new CurrentUser("actor","actor","Synthetic",Set.of("AUDITOR"));
        final List<OperationAuditEvent> audits=new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger parserCalls=new AtomicInteger();
        Function<ProjectFile,ParsedProjectDocument> parserBehavior=ignored->parsed();
        OcrPort ocr=request->{throw new AssertionError("Unexpected OCR call");};
        final FileSystemStorageAdapter storage;
        final Project project;
        final ProjectFileIngestionService ingestion;
        final ProjectFileProcessingService processing;
        Fixture() {
            var projects=new ProjectApplicationService(repositories.projectRepository(),repositories.projectFileRepository(),
                    repositories.contractCandidateRepository(),repositories.extractionResultRepository(),clock);
            project=projects.createBlank(actor.userId());
            var properties=new FileStorageProperties();properties.setRoot(root.resolve(UUID.randomUUID().toString()).toString());
            storage=spy(new FileSystemStorageAdapter(properties));
            var policy=new ProjectFilePolicy(ProjectIntakeProperties.defaults(),Duration.ofMinutes(15));
            var coordinator=new ProjectFileIngestionTransactionCoordinator(repositories.projectRepository(),repositories.projectFileRepository(),
                    repositories.contractCandidateRepository(),repositories.extractionResultRepository(),parses,new InMemoryIdempotencyAdapter(),
                    audits::add,clock,new FlowDebugLogger(new ObservabilityProperties()));
            var archives=new ProjectArchiveProcessingService(new SelectedProjectArchiveReader(ProjectIntakeProperties.defaults()),
                    new ProjectFileClassifier(),policy,new ProjectArchiveStorageKeyFactory(),storage);
            var parser=new ProjectDocumentParser() {
                public boolean supports(ProjectFile file){return file.originalFilename().endsWith(".docx");}
                public ParsedProjectDocument parse(ProjectFile file,InputStream input){parserCalls.incrementAndGet();return parserBehavior.apply(file);}
            };
            ingestion=spy(new ProjectFileIngestionService(projects,storage,policy,new ProjectFileClassifier(),List.of(parser),request->ocr.recognize(request),
                    new ProjectFactExtractor(new ExtractionKeyFactory(),clock),new ContractCandidateAssembler(clock),archives,coordinator,
                    ()->Optional.of(actor),clock));
            processing=new ProjectFileProcessingService(ingestion,repositories.projectFileRepository(),parses,storage,coordinator,jobs,clock);
        }
        ProjectFile accept(String name,String media,byte[] bytes,String key) {
            return ingestion.acceptAsync(new ProjectFileUploadCommand(project.id(),name,media,bytes.length,new ByteArrayInputStream(bytes),key,
                    new ProjectRequestMetadata("request","trace","127.0.0.1")),jobs);
        }
    }
}
