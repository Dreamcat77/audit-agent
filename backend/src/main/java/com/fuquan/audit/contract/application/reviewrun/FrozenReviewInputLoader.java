package com.fuquan.audit.contract.application.reviewrun;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

/** Reopens exact run/parse identities plus immutable file metadata bound by the frozen file ID/SHA tuple. */
@Component
public class FrozenReviewInputLoader {
    private final ContractReviewRunService runs;
    private final ProjectFileParseResultRepository parses;
    private final ProjectFileRepository files;
    private final ContractSnapshotDecoder decoder;

    @Autowired
    public FrozenReviewInputLoader(ContractReviewRunService runs,
            ProjectFileParseResultRepository parses, ProjectFileRepository files,
            ContractSnapshotDecoder decoder) {
        this.runs = runs;
        this.parses = parses;
        this.files = files;
        this.decoder = decoder;
    }

    public FrozenReviewInputLoader(ContractReviewRunService runs,
            ProjectFileParseResultRepository parses, ContractSnapshotDecoder decoder) {
        this.runs = runs;
        this.parses = parses;
        this.files = null;
        this.decoder = decoder;
    }

    @Transactional(readOnly = true)
    public FrozenReviewInput load(UUID projectId, UUID contractId, UUID runId) {
        var durable = runs.get(projectId, contractId, runId);
        var run = durable.run();
        var contract = decoder.decodeAndVerify(run);
        var materials = durable.materials().stream()
                .sorted(Comparator.comparingInt((com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial
                        value) -> value.ordinal()).thenComparing(value -> value.runMaterialId().toString()))
                .map(material -> {
                    var parse = parses.findById(material.parseResultId()).orElseThrow(FrozenReviewInputLoader::conflict);
                    if (!parse.parseResultId().equals(material.parseResultId())
                            || !parse.projectId().equals(run.projectId())
                            || !parse.fileId().equals(material.fileId())
                            || !parse.fileSha256().equals(material.fileSha256())
                            || parse.parseVersion() != material.parseVersion()
                            || parse.parseStatus() != ProjectFileParseStatus.PARSED
                            || !parse.canonicalTextSha256().equals(material.canonicalTextSha256())
                            || !ContractSnapshotCanonicalizer.sha256(parse.canonicalText())
                                     .equals(material.canonicalTextSha256())) throw conflict();
                    String originalFilename = null;
                    if (files != null) {
                        var sourceFile = files.findByProjectIdAndId(run.projectId(), material.fileId())
                                .orElseThrow(FrozenReviewInputLoader::conflict);
                        if (!sourceFile.id().equals(material.fileId())
                                || !sourceFile.projectId().equals(run.projectId())
                                || !sourceFile.sha256().equals(material.fileSha256())) throw conflict();
                        originalFilename = sourceFile.originalFilename();
                    }
                    return new FrozenReviewMaterial(material.runMaterialId(), material.ordinal(),
                            material.fileId(), material.fileSha256(), parse.parseResultId(),
                            parse.parseVersion(), parse.canonicalTextSha256(), parse.canonicalText(),
                            material.effectiveRole(), material.roleDecisionId(),
                            material.identityDecisionId(), material.sourceRoleDispositionId(), originalFilename);
                }).toList();
        return new FrozenReviewInput(run, contract, materials);
    }

    private static BusinessException conflict() {
        return new BusinessException(BusinessErrorCode.CONFLICT,
                "review run frozen parse cannot be verified");
    }
}
