package com.fuquan.audit.contract.application.project.factai;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentityResolver;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleResolver;
import com.fuquan.audit.contract.application.project.role.EffectiveDocumentRoleSource;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.EffectiveProjectIdentityStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import com.fuquan.audit.common.web.RequestIdFilter;
import org.springframework.stereotype.Component;

/** Resolves the exact human-confirmed file/parse authority tuple used by A-mini. */
@Component
public class DefaultContractFactExtractionAuthorityResolver
        implements ContractFactExtractionAuthorityResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            DefaultContractFactExtractionAuthorityResolver.class);
    private final ProjectRepository projects;
    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parses;
    private final EffectiveDocumentRoleResolver roles;
    private final EffectiveProjectIdentityResolver identities;

    public DefaultContractFactExtractionAuthorityResolver(ProjectRepository projects,
            ProjectFileRepository files, ProjectFileParseResultRepository parses,
            EffectiveDocumentRoleResolver roles, EffectiveProjectIdentityResolver identities) {
        this.projects = projects;
        this.files = files;
        this.parses = parses;
        this.roles = roles;
        this.identities = identities;
    }

    @Override
    public ContractFactExtractionAuthority resolve(UUID projectId, UUID fileId) {
        var project = projects.findById(projectId).orElseThrow(() -> reject(
                BusinessErrorCode.NOT_FOUND, "project not found", "PROJECT_NOT_FOUND", projectId, fileId));
        var file = files.findByProjectIdAndId(projectId, fileId)
                .orElseThrow(() -> reject(BusinessErrorCode.NOT_FOUND, "project file not found",
                        "FILE_NOT_FOUND", projectId, fileId));
        var parse = parses.findLatestByFileId(fileId)
                .orElseThrow(() -> reject(BusinessErrorCode.CONFLICT,
                        "immutable parse result is required", "PARSE_RESULT_MISSING", projectId, fileId));
        var role = roles.resolve(file);
        var identity = identities.resolve(project, file);
        var identityDecision = identity.currentHumanSameDecision();
        boolean supportedRole = role.effectiveRole() == DocumentRole.CONTRACT_BODY
                || role.effectiveRole() == DocumentRole.AWARD_NOTICE;
        if (file.ingestionStatus() != ProjectFileIngestionStatus.AVAILABLE) {
            throw reject("FILE_NOT_AVAILABLE", projectId, fileId);
        }
        if (file.parseStatus() != ProjectFileParseStatus.PARSED) {
            throw reject("PARSE_NOT_CURRENT", projectId, fileId);
        }
        if (parse.parseStatus() != ProjectFileParseStatus.PARSED) {
            throw reject("PARSE_NOT_CURRENT", projectId, fileId);
        }
        if (!parse.projectId().equals(projectId) || !parse.fileId().equals(fileId)) {
            throw reject("PARSE_NOT_CURRENT", projectId, fileId);
        }
        if (!parse.fileSha256().equals(file.sha256())) {
            throw reject("PARSE_SHA_MISMATCH", projectId, fileId);
        }
        if (!Objects.equals(parse.canonicalText(), file.parsedText())) {
            throw reject("PARSE_NOT_CURRENT", projectId, fileId);
        }
        if (!role.source().isAuthoritativeDecision()
                || role.roleDecisionId() == null || role.parseResultId() == null) {
            throw reject("ROLE_DECISION_MISSING", projectId, fileId);
        }
        if (!role.parseResultId().equals(parse.parseResultId())
                || role.parseVersion() != parse.parseVersion()) {
            throw reject("ROLE_PARSE_MISMATCH", projectId, fileId);
        }
        if (!supportedRole) throw reject("ROLE_NOT_SUPPORTED", projectId, fileId);
        if (!project.metadataConfirmed()) {
            throw reject("PROJECT_METADATA_UNCONFIRMED", projectId, fileId);
        }
        if (identity.status() != EffectiveProjectIdentityStatus.SAME_PROJECT) {
            throw reject("IDENTITY_NOT_SAME_PROJECT", projectId, fileId);
        }
        if (identityDecision == null) {
            throw reject("IDENTITY_DECISION_MISSING", projectId, fileId);
        }
        if (!identityDecision.projectId().equals(projectId)) {
            throw reject("IDENTITY_DECISION_MISMATCH", projectId, fileId);
        }
        if (identityDecision.projectVersionNo() != project.versionNo()) {
            throw reject("PROJECT_VERSION_MISMATCH", projectId, fileId);
        }
        if (!identityDecision.fileId().equals(fileId)
                || !identityDecision.fileSha256().equals(file.sha256())) {
            throw reject("IDENTITY_DECISION_MISMATCH", projectId, fileId);
        }
        if (!identityDecision.parseResultId().equals(parse.parseResultId())
                || identityDecision.parseVersion() != parse.parseVersion()) {
            throw reject("IDENTITY_PARSE_MISMATCH", projectId, fileId);
        }
        log("MATCHING", "NONE", projectId, fileId);
        var input = new ContractFactCandidateValidator.CurrentInput(projectId, fileId, file.sha256(),
                parse.parseResultId(), parse.parseVersion(), parse.canonicalTextSha256(),
                parse.canonicalText(), role.effectiveRole(), true);
        return new ContractFactExtractionAuthority(input, role.roleDecisionId(),
                identityDecision.decisionId(),
                parse.parseMethod()!=com.fuquan.audit.contract.domain.project.ProjectFileParseMethod.DOCX_TEXT
                        || com.fuquan.audit.contract.application.project.parse.ProjectDocxTextParser.CANONICAL_LAYOUT_VERSION
                                .equals(parse.engineVersion()));
    }

    private static BusinessException reject(String reasonCode, UUID projectId, UUID fileId) {
        return reject(BusinessErrorCode.CONFLICT,
                "contract fact extraction authority is not current", reasonCode, projectId, fileId);
    }

    private static BusinessException reject(BusinessErrorCode errorCode, String message,
            String reasonCode, UUID projectId, UUID fileId) {
        log("REJECTED", reasonCode, projectId, fileId);
        return new BusinessException(errorCode, message);
    }

    private static void log(String outcome, String reasonCode, UUID projectId, UUID fileId) {
        LOGGER.info("event=AUTHORITY_CHECK diagnosticSchemaVersion=1 module=CONTRACT layer=APPLICATION "
                        + "operation=resolveContractFactAuthority stage=AUTHORITY_CHECK outcome={} "
                        + "reasonCode={} requestId={} traceId={} projectId={} fileId={} attemptId=null "
                        + "idempotencyScope=null operationKeyHash=null durationMs=0",
                outcome, reasonCode, MDC.get(RequestIdFilter.MDC_KEY), MDC.get("traceId"),
                projectId, fileId);
    }
}
