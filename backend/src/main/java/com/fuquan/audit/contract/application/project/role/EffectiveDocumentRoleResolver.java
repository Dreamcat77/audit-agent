package com.fuquan.audit.contract.application.project.role;

import com.fuquan.audit.contract.application.project.AutomaticDecisionProvenance;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRoleDecisionRepository;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseOrigin;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleDecision;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class EffectiveDocumentRoleResolver {
    private final ProjectFileParseResultRepository parses;
    private final ProjectFileRoleDecisionRepository decisions;

    public EffectiveDocumentRoleResolver(ProjectFileParseResultRepository parses,
                                         ProjectFileRoleDecisionRepository decisions) {
        this.parses = parses;
        this.decisions = decisions;
    }

    public EffectiveDocumentRoleResult resolve(ProjectFile file) {
        Objects.requireNonNull(file, "file must not be null");
        Optional<ProjectFileParseResult> currentParse = parses.findLatestByFileId(file.id());
        if (currentParse.isEmpty()) return unknown(null);
        ProjectFileParseResult parse = currentParse.orElseThrow();

        Optional<ProjectFileRoleDecision> latest = decisions.findLatestByFileId(file.id());
        if (latest.isPresent() && valid(latest.orElseThrow(), file, parse)) {
            ProjectFileRoleDecision decision = latest.orElseThrow();
            EffectiveDocumentRoleSource source = AutomaticDecisionProvenance.isAutomatic(decision.reason())
                    ? EffectiveDocumentRoleSource.SYSTEM_AUTO_DECISION
                    : EffectiveDocumentRoleSource.HUMAN_CONFIRMATION;
            return new EffectiveDocumentRoleResult(decision.selectedRole(),
                    source, decision.decisionId(), decision.versionNo(),
                    parse.parseResultId(), parse.parseVersion());
        }

        if (latest.isEmpty() && legacyEligible(file, parse)) {
            DocumentRole role = legacyRole(file.category());
            if (role != DocumentRole.UNKNOWN) {
                return new EffectiveDocumentRoleResult(role, EffectiveDocumentRoleSource.LEGACY_EFFECTIVE,
                        null, null, parse.parseResultId(), parse.parseVersion());
            }
        }
        return unknown(parse);
    }

    private static boolean valid(ProjectFileRoleDecision decision, ProjectFile file,
                                 ProjectFileParseResult parse) {
        return file.ingestionStatus() == ProjectFileIngestionStatus.AVAILABLE
                && file.parseStatus() == ProjectFileParseStatus.PARSED
                && parse.parseStatus() == ProjectFileParseStatus.PARSED
                && Objects.equals(parse.canonicalText(), file.parsedText())
                && decision.projectId().equals(file.projectId())
                && decision.fileId().equals(file.id())
                && decision.fileSha256().equals(file.sha256())
                && decision.parseResultId().equals(parse.parseResultId())
                && decision.parseVersion() == parse.parseVersion();
    }

    private static boolean legacyEligible(ProjectFile file, ProjectFileParseResult parse) {
        return file.ingestionStatus() == ProjectFileIngestionStatus.AVAILABLE
                && file.parseStatus() == ProjectFileParseStatus.PARSED
                && parse.parseStatus() == ProjectFileParseStatus.PARSED
                && parse.parseOrigin() == ProjectFileParseOrigin.LEGACY_IMPORTED
                && Objects.equals(parse.canonicalText(), file.parsedText())
                && parse.projectId().equals(file.projectId())
                && parse.fileId().equals(file.id())
                && parse.fileSha256().equals(file.sha256());
    }

    private static DocumentRole legacyRole(ProjectFileCategory category) {
        return switch (category) {
            case CONTRACT_BODY -> DocumentRole.CONTRACT_BODY;
            case AWARD_NOTICE -> DocumentRole.AWARD_NOTICE;
            case TENDER_DOCUMENT -> DocumentRole.TENDER_DOCUMENT;
            case BID_RESPONSE -> DocumentRole.BID_RESPONSE;
            case SUPPLEMENT -> DocumentRole.SUPPLEMENT;
            case OTHER -> DocumentRole.OTHER;
            case PACKAGE, UNKNOWN -> DocumentRole.UNKNOWN;
        };
    }

    private static EffectiveDocumentRoleResult unknown(ProjectFileParseResult parse) {
        return new EffectiveDocumentRoleResult(DocumentRole.UNKNOWN,
                EffectiveDocumentRoleSource.NO_AUTHORITATIVE_ROLE, null, null,
                parse == null ? null : parse.parseResultId(), parse == null ? null : parse.parseVersion());
    }
}
