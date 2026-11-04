package com.fuquan.audit.contract.api.project;

import com.fuquan.audit.security.domain.CurrentUser;
import com.fuquan.audit.contract.test.ProjectFileFixtures;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.fuquan.audit.contract.application.project.ProjectApplicationService;
import com.fuquan.audit.integration.file.FileStorageProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ProjectApiIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ProjectApplicationService projects;
    @Autowired FileStorageProperties storageProperties;
    @Autowired com.fuquan.audit.contract.application.project.ProjectFileProcessingService processing;
    @Autowired com.fuquan.audit.contract.application.project.port.ProjectFileJobRepository fileJobs;
    @Autowired com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository parseResults;

    @Test void defaultLocalAsyncDocxCompletesWithoutDm8RedisOrRealOcr() throws Exception {
        var actor=auth();var project=projects.createBlank(((CurrentUser)actor.getPrincipal()).userId());
        byte[] bytes;
        try(var document=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Synthetic contract: 42.50");document.write(out);bytes=out.toByteArray();
        }
        var response=mockMvc.perform(multipart("/api/v1/projects/{id}/file-jobs",project.id())
                .file(new MockMultipartFile("file","synthetic-local.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes))
                .header("Idempotency-Key","local-async-"+UUID.randomUUID()).with(csrf()).with(authentication(actor)))
                .andExpect(status().isAccepted()).andReturn();
        String fileId=com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(),"$.data.fileId");
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while(!"SUCCEEDED".equals(fileJobs.byFile(project.id(),UUID.fromString(fileId)).orElseThrow().status())
                &&System.nanoTime()<deadline)Thread.sleep(10);
        assertThat(fileJobs.byFile(project.id(),UUID.fromString(fileId)).orElseThrow().status()).isEqualTo("SUCCEEDED");
        assertThat(projects.file(project.id(),UUID.fromString(fileId)).parseStatus()).isEqualTo(
                com.fuquan.audit.contract.domain.project.ProjectFileParseStatus.PARSED);
        assertThat(parseResults.findLatestByFileId(UUID.fromString(fileId)).orElseThrow().engineVersion())
                .isEqualTo(com.fuquan.audit.common.material.application.port.MaterialParsePort.PARSER_VERSION);
    }

    @Test void defaultLocalAsyncZipSchedulesItsChildrenWithTheAcceptedActor() throws Exception {
        var actor=auth();var project=projects.createBlank(((CurrentUser)actor.getPrincipal()).userId());
        byte[] documentBytes;
        try(var document=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Synthetic local ZIP child");
            document.write(out);documentBytes=out.toByteArray();
        }
        byte[] archive;
        try(var out=new ByteArrayOutputStream();var zip=new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("synthetic-child.docx"));zip.write(documentBytes);zip.closeEntry();
            zip.finish();archive=out.toByteArray();
        }
        mockMvc.perform(multipart("/api/v1/projects/{id}/file-jobs",project.id())
                .file(new MockMultipartFile("file","synthetic-local.zip","application/zip",archive))
                .header("Idempotency-Key","local-zip-"+UUID.randomUUID()).with(csrf()).with(authentication(actor)))
                .andExpect(status().isAccepted());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<deadline) {
            var jobs=fileJobs.byProject(project.id());
            if(jobs.size()==2&&jobs.stream().allMatch(job->"SUCCEEDED".equals(job.status())))break;
            Thread.sleep(10);
        }
        assertThat(fileJobs.byProject(project.id())).hasSize(2)
                .allMatch(job->"SUCCEEDED".equals(job.status()),"ZIP parent and child independently complete");
        assertThat(fileJobs.byProject(project.id())).allMatch(job->job.actorId().equals(((CurrentUser)actor.getPrincipal()).userId()));
    }

    @Test
    void asynchronous202HasNoParsedFactUntilWorkerAndSameKeyReopensOnlyTheOriginalActorOperation() throws Exception {
        Authentication actor=auth();String createKey="async-create-"+UUID.randomUUID();
        var create=mockMvc.perform(post("/api/v1/projects").header("Idempotency-Key",createKey)
                .with(csrf()).with(authentication(actor))).andExpect(status().isOk()).andReturn();
        String projectId=com.jayway.jsonpath.JsonPath.read(create.getResponse().getContentAsString(),"$.data.id");
        mockMvc.perform(get("/api/v1/projects/creation-operations").param("key",createKey).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(projectId));
        var file=new MockMultipartFile("file","synthetic-contract.pdf","application/pdf",pdf("Synthetic contract amount: 42.50"));
        String uploadKey="async-upload-"+UUID.randomUUID();
        var accepted=mockMvc.perform(multipart("/api/v1/projects/{id}/file-jobs",projectId).file(file)
                .header("Idempotency-Key",uploadKey).header("X-Request-Id","async-accepted-request")
                .with(csrf()).with(authentication(actor)))
                .andExpect(status().isAccepted()).andExpect(header().string("X-Request-Id","async-accepted-request"))
                .andExpect(jsonPath("$.data.file.parseStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.job.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.job.leaseToken").doesNotExist())
                .andExpect(jsonPath("$.data.file.storageKey").doesNotExist()).andReturn();
        String fileId=com.jayway.jsonpath.JsonPath.read(accepted.getResponse().getContentAsString(),"$.data.fileId");
        String jobId=com.jayway.jsonpath.JsonPath.read(accepted.getResponse().getContentAsString(),"$.data.jobId");
        mockMvc.perform(multipart("/api/v1/projects/{id}/file-jobs",projectId).file(file)
                .header("Idempotency-Key",uploadKey).with(csrf()).with(authentication(actor)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.jobId").value(jobId));
        mockMvc.perform(get("/api/v1/projects/{id}/file-jobs/by-operation",projectId).param("key",uploadKey)
                .with(authentication(actor))).andExpect(status().isOk()).andExpect(jsonPath("$.data.fileId").value(fileId));
        mockMvc.perform(get("/api/v1/projects/{id}/file-jobs",projectId).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.completed").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.equalTo(0),org.hamcrest.Matchers.equalTo(1))))
                .andExpect(jsonPath("$.data.workerEnabled").value(true));
        var stranger=new CurrentUser("different-async-actor","differentasync","Other",Set.of("AUDITOR"));
        var strangerAuth=new UsernamePasswordAuthenticationToken(stranger,null,List.of(new SimpleGrantedAuthority("ROLE_AUDITOR")));
        mockMvc.perform(get("/api/v1/projects/{id}/file-jobs/by-operation",projectId).param("key",uploadKey)
                .with(authentication(strangerAuth))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/projects/creation-operations").param("key",createKey)
                .with(authentication(strangerAuth))).andExpect(status().isNotFound());
        mockMvc.perform(multipart("/api/v1/projects/{id}/file-jobs",projectId).file(file)
                .header("Idempotency-Key","no-csrf").with(authentication(actor))).andExpect(status().isForbidden());
        processing.process(UUID.fromString(jobId),(CurrentUser)actor.getPrincipal(),token->{});
        // The enabled local worker may already own this exact job. A second executor
        // must not steal it; await the durable terminal result instead of assuming
        // that the competing process() invocation completed synchronously.
        long completeBy=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<completeBy&&fileJobs.byFile(UUID.fromString(projectId),UUID.fromString(fileId))
                .map(job->Set.of("QUEUED","PROCESSING").contains(job.status())).orElse(false))Thread.sleep(10);
        mockMvc.perform(get("/api/v1/projects/{id}/files/{fileId}/processing",projectId,fileId).with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.jobId").value(jobId))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.parseResultId").isNotEmpty());
        assertThat(fileJobs.byProject(UUID.fromString(projectId))).hasSize(1);
    }

    @Test
    void blankCreatePatchUploadReadCandidateContentAndRecoveryUseAuthenticatedOwnedRoutes() throws Exception {
        Authentication auth = auth();
        MvcResult create = mockMvc.perform(post("/api/v1/projects")
                        .header("Idempotency-Key", "project-create-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        String projectId = com.jayway.jsonpath.JsonPath.read(create.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(patch("/api/v1/projects/{projectId}", projectId)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":0,\"name\":\"Project Alpha\"}")
                        .header("Idempotency-Key", "project-patch-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.versionNo").value(1));

        MockMultipartFile file = new MockMultipartFile("file", "contract.pdf", "application/pdf",
                pdf("Project Name: Project Alpha\n"
                        + "Project No: P-001\n"
                        + "Contract No: API-1\n"
                        + "Contract Name: Equipment Supply\n"
                        + "Party A: Buyer\n"
                        + "Party B: Seller\n"
                        + "Supplier: Seller\n"
                        + "Credit Code: 123456789012345678\n"
                        + "Contract Amount: 99.50\n"
                        + "Amount Uppercase: NINETY NINE\n"
                        + "Line Item [ITEM-1] Name: Server Quantity: 2 Unit Price: 40.00 Declared Amount: 80.00\n"
                        + "Line Item [ITEM-2] Name: Storage Quantity: 1 Unit Price: 19.50 Declared Amount: 19.50\n"
                        + "Winning Amount: 90.00\n"
                        + "Winning Supplier: Seller\n"
                        + "Winning Credit Code: 123456789012345678"));
        MvcResult upload = mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(file).header("Idempotency-Key", "project-upload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ingestionStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.storageKey").doesNotExist())
                .andExpect(jsonPath("$.data.sha256").doesNotExist())
                .andReturn();
        String fileId = com.jayway.jsonpath.JsonPath.read(upload.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/content", projectId, fileId)
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"));
        confirmContractRole(projectId, fileId, auth);
        mockMvc.perform(get("/api/v1/projects/{projectId}/contract-candidates", projectId)
                        .with(authentication(auth)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/projects/{projectId}/contract-candidates/{candidateId}", projectId,
                        firstCandidateId(projectId, auth)).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldStates.CONTRACT_NO").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.fieldStates.PROJECT_NAME").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.fieldStates.LINE_ITEM_NAME").doesNotExist())
                .andExpect(jsonPath("$.data.lineItemFieldStates['ITEM-1'].LINE_ITEM_NAME").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.lineItemFieldStates['ITEM-1'].LINE_ITEM_QUANTITY").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.lineItemFieldStates['ITEM-2'].LINE_ITEM_NAME").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.lineItemFieldStates['ITEM-2'].LINE_ITEM_QUANTITY").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.candidate.contractName").value("Equipment Supply"))
                .andExpect(jsonPath("$.data.candidate.lineItems.length()").value(2))
                .andExpect(jsonPath("$.data.candidate.lineItems[0].itemKey").value("ITEM-1"))
                .andExpect(jsonPath("$.data.candidate.lineItems[1].itemKey").value("ITEM-2"));
        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/recover", projectId, fileId)
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(fileId));
    }

    @Test
    void projectRoutesRemainProtectedByAuthenticationAndCsrf() throws Exception {
        mockMvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/projects/preparation-drafts")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/projects").header("Idempotency-Key", "missing-csrf")
                        .with(authentication(auth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void ordinaryUploadedFilesAreRediscoveredThroughPreparationDraftReadModel() throws Exception {
        Authentication auth = auth();
        MvcResult create = mockMvc.perform(post("/api/v1/projects")
                        .header("Idempotency-Key", "preparation-create-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String projectId = com.jayway.jsonpath.JsonPath.read(
                create.getResponse().getContentAsString(), "$.data.id");
        MockMultipartFile file = new MockMultipartFile("file", "ordinary.pdf", "application/pdf",
                pdf("ordinary preparation material"));
        mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(file).header("Idempotency-Key", "preparation-upload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/projects/preparation-drafts").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.projectId == '%s')]", projectId).exists())
                .andExpect(jsonPath("$.data.items[?(@.projectId == '%s')].uploadedFileCount", projectId)
                        .value(org.hamcrest.Matchers.contains(1)));
        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.project.id").value(projectId))
                .andExpect(jsonPath("$.data.files.length()").value(1));
    }

    @Test
    void directUploadRejectsMissingMimeAndSignatureMismatchAsBadRequests() throws Exception {
        Authentication auth = auth();
        MvcResult create = mockMvc.perform(post("/api/v1/projects")
                        .header("Idempotency-Key", "policy-project-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String projectId = com.jayway.jsonpath.JsonPath.read(create.getResponse().getContentAsString(), "$.data.id");

        MockMultipartFile missingMime = new MockMultipartFile("file", "contract.pdf", null, "%PDF-1.4".getBytes());
        mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(missingMime).header("Idempotency-Key", "missing-mime-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isBadRequest());

        MockMultipartFile spoofedPdf = new MockMultipartFile("file", "contract.pdf", "application/pdf",
                "not a pdf".getBytes());
        mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(spoofedPdf).header("Idempotency-Key", "spoofed-pdf-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void contentDownloadRejectsBytesWhoseDurableSizeOrHashNoLongerMatches() throws Exception {
        Authentication auth = auth();
        MvcResult create = mockMvc.perform(post("/api/v1/projects")
                        .header("Idempotency-Key", "integrity-project-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String projectId = com.jayway.jsonpath.JsonPath.read(create.getResponse().getContentAsString(), "$.data.id");
        MockMultipartFile upload = new MockMultipartFile("file", "integrity.pdf", "application/pdf",
                pdf("Contract No: INTEGRITY-1"));
        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(upload).header("Idempotency-Key", "integrity-upload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String fileId = com.jayway.jsonpath.JsonPath.read(uploaded.getResponse().getContentAsString(), "$.data.id");
        var file = projects.file(UUID.fromString(projectId), UUID.fromString(fileId));
        java.nio.file.Path target = java.nio.file.Path.of(storageProperties.getRoot(), "objects",
                file.storageKey().substring(0, 2), file.storageKey() + ".bin");
        java.nio.file.Files.writeString(target, "tampered", java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

        mockMvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/content", projectId, fileId)
                        .with(authentication(auth)))
                .andExpect(status().isConflict());
    }

    @Test
    void safePackageUploadCreatesOwnedChildAndCandidateWithoutExposingStorageIdentity() throws Exception {
        Authentication auth = auth();
        MvcResult create = mockMvc.perform(post("/api/v1/projects")
                        .header("Idempotency-Key", "archive-project-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String projectId = com.jayway.jsonpath.JsonPath.read(create.getResponse().getContentAsString(), "$.data.id");
        MockMultipartFile archive = new MockMultipartFile("file", "intake.zip", "application/zip",
                zip("合同/contract.pdf", pdf("Contract No ZIP-1 Contract Amount: 20.00")));

        mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(archive).header("Idempotency-Key", "archive-upload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("PACKAGE"));
        MvcResult fileList = mockMvc.perform(get("/api/v1/projects/{projectId}/files", projectId)
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].storageKey").doesNotExist())
                .andReturn();
        List<Map<String, Object>> uploadedFiles = com.jayway.jsonpath.JsonPath.read(
                fileList.getResponse().getContentAsString(), "$.data");
        String contractFileId = uploadedFiles.stream()
                .filter(value -> value.get("parentArchiveFileId") != null)
                .map(value -> String.valueOf(value.get("id"))).findFirst().orElseThrow();
        confirmContractRole(projectId, contractFileId, auth);
        mockMvc.perform(get("/api/v1/projects/{projectId}/contract-candidates", projectId)
                        .with(authentication(auth)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void packageIntakeAppearsOnlyAsDraftUntilMetadataConfirmationThenReusesTheSameProjectFiles() throws Exception {
        Authentication auth = auth();
        MvcResult create = mockMvc.perform(post("/api/v1/projects")
                        .header("Idempotency-Key", "draft-project-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metadataConfirmed").value(false))
                .andReturn();
        String projectId = com.jayway.jsonpath.JsonPath.read(create.getResponse().getContentAsString(), "$.data.id");
        MockMultipartFile archive = new MockMultipartFile("file", "preprocessed-intake.zip", "application/zip",
                zip("合同/contract.pdf", pdf("Project Name: Draft Candidate\nProject No: DRAFT-2026-001\nContract No: DRAFT-C-1")));
        mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(archive).header("Idempotency-Key", "draft-upload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk());

        List<Map<String, Object>> formalBefore = projectItems(mockMvc.perform(get("/api/v1/projects")
                .with(authentication(auth))).andExpect(status().isOk()).andReturn());
        assertThat(formalBefore).noneMatch(project -> projectId.equals(project.get("id")));
        MvcResult draftResult = mockMvc.perform(get("/api/v1/projects/intake-drafts")
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> drafts = com.jayway.jsonpath.JsonPath.read(
                draftResult.getResponse().getContentAsString(), "$.data.items");
        Map<String, Object> draft = drafts.stream().filter(value -> projectId.equals(value.get("projectId")))
                .findFirst().orElseThrow();
        assertThat(draft).containsEntry("packageFilename", "preprocessed-intake.zip")
                .containsEntry("candidateProjectName", "Draft Candidate")
                .containsEntry("candidateProjectNo", "DRAFT-2026-001");
        List<String> fileIdsBefore = projectFileIds(projectId, auth);

        mockMvc.perform(patch("/api/v1/projects/{projectId}", projectId)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":0,\"name\":\"Draft Candidate\",\"projectNo\":\"DRAFT-2026-001\",\"category\":\"办公设备\"}")
                        .header("Idempotency-Key", "draft-confirm-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(projectId))
                .andExpect(jsonPath("$.data.metadataConfirmed").value(true));

        MvcResult formalAfter = mockMvc.perform(get("/api/v1/projects")
                        .param("keyword", "DRAFT-2026-001").with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        assertThat(projectItems(formalAfter)).extracting(value -> value.get("id")).containsExactly(projectId);
        MvcResult draftsAfter = mockMvc.perform(get("/api/v1/projects/intake-drafts")
                        .with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> remainingDrafts = com.jayway.jsonpath.JsonPath.read(
                draftsAfter.getResponse().getContentAsString(), "$.data.items");
        assertThat(remainingDrafts).noneMatch(value -> projectId.equals(value.get("projectId")));
        assertThat(projectFileIds(projectId, auth)).containsExactlyElementsOf(fileIdsBefore);
    }

    @Test
    void formatMatrixApiKeepsUnsupportedMaterialsAvailableAndReturnsOriginalImages() throws Exception {
        Authentication auth = auth();
        MvcResult create = mockMvc.perform(post("/api/v1/projects")
                        .header("Idempotency-Key", "format-api-project-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String projectId = com.jayway.jsonpath.JsonPath.read(create.getResponse().getContentAsString(), "$.data.id");
        MockMultipartFile archive = new MockMultipartFile("file", "project_file_format_matrix.zip",
                "application/zip", ProjectFileFixtures.bytes("project_file_format_matrix.zip"));

        mockMvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(archive).header("Idempotency-Key", "format-api-upload-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("PACKAGE"))
                .andExpect(jsonPath("$.data.parseStatus").value("NOT_APPLICABLE"));
        MvcResult listing = mockMvc.perform(get("/api/v1/projects/{projectId}/files", projectId)
                        .with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> files = com.jayway.jsonpath.JsonPath.read(
                listing.getResponse().getContentAsString(), "$.data");

        assertThat(files).hasSize(11);
        Map<String, Object> xlsx = file(files, "04_其他材料/明细表.xlsx");
        Map<String, Object> nested = file(files, "04_其他材料/nested.zip");
        assertThat(xlsx).containsEntry("ingestionStatus", "AVAILABLE")
                .containsEntry("parseStatus", "UNSUPPORTED").containsEntry("parsedText", null)
                .containsEntry("parseMethod", null).containsEntry("parseEngine", null)
                .containsEntry("parseConfidence", null).containsEntry("pageCount", null)
                .containsEntry("parseDurationMs", null);
        assertThat(nested).containsEntry("ingestionStatus", "AVAILABLE")
                .containsEntry("parseStatus", "UNSUPPORTED");
        assertThat(files).noneMatch(value -> String.valueOf(value.get("normalizedRelativePath"))
                .contains("second-level"));

        assertImageContent(projectId, auth, file(files, "04_其他材料/现场照片.png"), "valid.png");
        assertImageContent(projectId, auth, file(files, "04_其他材料/现场照片.jpg"), "valid.jpg");
        assertImageContent(projectId, auth, file(files, "04_其他材料/现场照片.jpeg"), "valid.jpeg");
    }

    private static Authentication auth() {
        CurrentUser user = new CurrentUser("project-api-user", "project.api", "Project API", Set.of("AUDITOR"));
        return UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_AUDITOR")));
    }

    private String firstCandidateId(String projectId, Authentication auth) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/contract-candidates", projectId)
                        .with(authentication(auth))).andExpect(status().isOk()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.data[0].id");
    }

    private void confirmContractRole(String projectId, String fileId, Authentication auth) throws Exception {
        MvcResult project = mockMvc.perform(get("/api/v1/projects/{projectId}", projectId)
                        .with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        int projectVersion = com.jayway.jsonpath.JsonPath.read(
                project.getResponse().getContentAsString(), "$.data.project.versionNo");
        if (projectVersion == 0) {
            mockMvc.perform(patch("/api/v1/projects/{projectId}", projectId)
                            .header("Idempotency-Key", "project-fixture-metadata-" + UUID.randomUUID())
                            .contentType("application/json")
                            .content("{\"expectedVersion\":0,\"name\":\"Synthetic intake project\"}")
                            .with(csrf()).with(authentication(auth)))
                    .andExpect(status().isOk());
        }
        MvcResult identity = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/identity-assessments", projectId, fileId)
                        .header("Idempotency-Key", "project-identity-assess-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String identityAssessmentId = com.jayway.jsonpath.JsonPath.read(
                identity.getResponse().getContentAsString(), "$.data.assessmentId");
        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/identity-confirmations",
                        projectId, fileId)
                        .header("Idempotency-Key", "project-identity-confirm-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(("{\"assessmentId\":\"%s\",\"resolution\":\"CONFIRM_SAME_PROJECT\","
                                + "\"expectedVersion\":0,\"reason\":\"synthetic ownership\"}")
                                .formatted(identityAssessmentId))
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk());
        MvcResult assessed = mockMvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-assessments", projectId, fileId)
                        .header("Idempotency-Key", "project-assess-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        String assessmentId = com.jayway.jsonpath.JsonPath.read(
                assessed.getResponse().getContentAsString(), "$.data.assessmentId");
        String body = ("{\"assessmentId\":\"%s\",\"selectedRole\":\"CONTRACT_BODY\","
                + "\"expectedVersion\":0,\"reason\":\"synthetic API fixture\"}").formatted(assessmentId);
        mockMvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        projectId, fileId).header("Idempotency-Key", "project-confirm-" + UUID.randomUUID())
                        .contentType("application/json").content(body)
                        .with(csrf()).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateId").isNotEmpty());
    }

    private static Map<String, Object> file(List<Map<String, Object>> files, String path) {
        return files.stream().filter(value -> path.equals(value.get("normalizedRelativePath")))
                .findFirst().orElseThrow();
    }

    private static List<Map<String, Object>> projectItems(MvcResult result) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.data.items");
    }

    private List<String> projectFileIds(String projectId, Authentication auth) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/files", projectId)
                        .with(authentication(auth)))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> values = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data");
        return values.stream().map(value -> String.valueOf(value.get("id"))).toList();
    }

    private void assertImageContent(String projectId, Authentication auth, Map<String, Object> file,
                                    String fixture) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/files/{fileId}/content",
                        projectId, file.get("id")).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(ProjectFileFixtures.bytes(fixture));
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(40, 700);
                for (String line : text.split("\\n")) {
                    content.showText(line);
                    content.newLineAtOffset(0, -16);
                }
                content.endText();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray();
        }
    }

    private static byte[] zip(String name, byte[] content) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name)); zip.write(content); zip.closeEntry();
        }
        return output.toByteArray();
    }
}
