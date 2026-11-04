package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.application.port.StagedFile;
import com.fuquan.audit.contract.application.port.StorageKey;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectIntakeRecoveryDeduplicationTest {
    @TempDir Path root;

    @Test
    void sameDurableOperationNeverCreatesCandidateOrDuplicatesExtractionOnRecoveryReplay() throws Exception {
        ProjectRecoveryTestSupport support = new ProjectRecoveryTestSupport(root);
        var pending = support.pending(true, false, null, null);
        ProjectFileRecoveryService recovery = support.recovery(support.durableStorage);

        recovery.recover(support.project.id(), pending.file().id(), ProjectRecoveryTestSupport.META);
        int candidates = support.repositories.contractCandidateRepository().findByProjectId(support.project.id()).size();
        int extractions = support.repositories.extractionResultRepository().findByProjectId(support.project.id()).size();
        recovery.recover(support.project.id(), pending.file().id(), ProjectRecoveryTestSupport.META);

        assertThat(candidates).isZero();
        assertThat(extractions).isPositive();
        assertThat(support.repositories.contractCandidateRepository().findByProjectId(support.project.id()))
                .hasSize(candidates);
        assertThat(support.repositories.extractionResultRepository().findByProjectId(support.project.id()))
                .hasSize(extractions);
        assertThat(support.idempotency.size()).isEqualTo(1);
    }

    @Test
    void archiveRecoveryRekeysFactsToTheAlreadyPersistedCanonicalChild() throws Exception {
        ProjectRecoveryTestSupport support = new ProjectRecoveryTestSupport(root);
        byte[] archive = zip("contract/contract.pdf", pdf("Contract No: ARC-1 Contract Amount: 12.00"));
        StagedFile staged = support.durableStorage.stage(new ByteArrayInputStream(archive),
                support.policy.properties().maxUploadSize().toBytes());
        UUID rootFileId = UUID.randomUUID();
        StorageKey rootKey = new ProjectFileStorageKeyFactory().create(rootFileId, staged.sha256());
        ProjectFile rootFile = new ProjectFile(rootFileId, support.project.id(), null, null,
                "intake.zip", "application/zip", ProjectFileCategory.PACKAGE,
                ProjectFileIngestionStatus.UPLOAD_PENDING, ProjectFileParseStatus.NOT_APPLICABLE,
                rootKey.value(), staged.sha256(), staged.size(), null, null,
                support.clock.instant(), support.clock.instant());
        var start = support.coordinator.startUpload(rootFile, support.actor, "archive-recovery-key",
                "archive-fingerprint", ProjectRecoveryTestSupport.META);
        support.durableStorage.promote(staged, rootKey);

        var interrupted = support.ingestion.processPersisted(rootFile);
        interrupted.children().forEach(support.repositories.projectFileRepository()::insert);
        Map<UUID, ProjectFileParseResult> parseWinnersByCanonicalFileId = new HashMap<>();
        for (ProjectFileParseResult proposed : interrupted.parseResults()) {
            ProjectFileParseResult stored = support.parseResults.insertIfAbsent(proposed);
            parseWinnersByCanonicalFileId.put(proposed.fileId(), stored);
        }
        for (ExtractionResult fact : interrupted.extractions()) {
            ProjectFileParseResult parseWinner = parseWinnersByCanonicalFileId.get(fact.sourceFileId());
            ExtractionResult v2Fact = new ExtractionResult(fact.id(), fact.projectId(), parseWinner.fileId(),
                    parseWinner.parseResultId(), null, new ExtractionKeyFactoryV2().create(parseWinner.fileId(),
                    parseWinner.parseResultId(), fact.extractionVersion(), fact.factType(), fact.groupKey(),
                    fact.sourceLocator()), fact.extractionVersion(), fact.factType(), fact.groupKey(),
                    fact.valueText(), fact.normalizedValue(), fact.valueAmount(), fact.sourceText(),
                    fact.pageNumber(), fact.paragraphIndex(), fact.sourceLocator(), fact.createdAt());
            support.repositories.extractionResultRepository().insertIfAbsent(v2Fact);
        }
        int extractionCount = interrupted.extractions().size();

        var replay = support.ingestion.processPersisted(rootFile);
        ProjectFileParseResult replayProposal = replay.parseResults().get(0);
        ProjectFileParseResult parseWinner = parseWinnersByCanonicalFileId.values().iterator().next();
        ProjectFileParseResult authoritativeReplayProposal = new ProjectFileParseResult(
                UUID.randomUUID(), parseWinner.projectId(), replayProposal.fileId(),
                parseWinner.fileSha256(), parseWinner.parseVersion(), parseWinner.parseOrigin(),
                parseWinner.parseStatus(), parseWinner.parseMethod(), parseWinner.engine(),
                parseWinner.engineVersion(), parseWinner.canonicalText(), parseWinner.canonicalTextSha256(),
                parseWinner.pageCount(), parseWinner.durationMs(), parseWinner.confidence(),
                parseWinner.supersedesParseResultId(), parseWinner.createdAt(), parseWinner.completedAt(),
                parseWinner.versionNo());
        var authoritativeReplay = new ProjectFileIngestionTransactionCoordinator.ProcessingOutcome(
                replay.root(), replay.children(), replay.candidates(), replay.extractions(),
                java.util.List.of(authoritativeReplayProposal));
        assertThat(authoritativeReplayProposal.parseResultId()).isNotEqualTo(parseWinner.parseResultId());
        support.coordinator.completeUpload(start, authoritativeReplay, support.actor, ProjectRecoveryTestSupport.META);

        assertThat(support.repositories.projectFileRepository().findByProjectId(support.project.id())).hasSize(2);
        assertThat(support.repositories.contractCandidateRepository().findByProjectId(support.project.id())).isEmpty();
        assertThat(support.repositories.extractionResultRepository().findByProjectId(support.project.id()))
                .hasSize(extractionCount).allSatisfy(fact -> assertThat(fact.extractionKey()).isEqualTo(
                        new ExtractionKeyFactoryV2().create(parseWinner.fileId(), parseWinner.parseResultId(),
                                fact.extractionVersion(), fact.factType(), fact.groupKey(), fact.sourceLocator())));
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(40, 700);
                content.showText(text);
                content.endText();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] zip(String name, byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(output)) {
            archive.putNextEntry(new ZipEntry(name));
            archive.write(bytes);
            archive.closeEntry();
        }
        return output.toByteArray();
    }
}
