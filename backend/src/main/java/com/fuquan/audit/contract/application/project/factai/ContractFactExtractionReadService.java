package com.fuquan.audit.contract.application.project.factai;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only audit projection. Reading never invokes the model or publishes facts. */
@Service
public class ContractFactExtractionReadService {
    private final ProjectRepository projects;
    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parses;
    private final ContractFactExtractionRepository attempts;
    private final ContractFactExtractionAuthorityResolver authority;
    private final ContractFactCandidatePort provider;

    public ContractFactExtractionReadService(ProjectRepository projects, ProjectFileRepository files,
            ProjectFileParseResultRepository parses, ContractFactExtractionRepository attempts,
            ContractFactExtractionAuthorityResolver authority, ContractFactCandidatePort provider) {
        this.projects = projects; this.files = files; this.parses = parses; this.attempts = attempts;
        this.authority = authority; this.provider = provider;
    }

    @Transactional(readOnly = true)
    public Projection latest(UUID projectId, UUID fileId) {
        projects.findById(projectId).orElseThrow(() -> notFound("project not found"));
        files.findByProjectIdAndId(projectId, fileId)
                .orElseThrow(() -> notFound("project file not found"));
        parses.findLatestByFileId(fileId)
                .orElseThrow(() -> notFound("project file parse result not found"));
        var current = authority.resolve(projectId, fileId);
        var input = current.current();
        var attempt = attempts.findLatestForAuthority(projectId, fileId, input.parseResultId(),
                current.roleDecisionId(), current.identityDecisionId(), input.effectiveRole(),
                provider.configuredProvenance()).orElse(null);
        if (attempt == null) return new Projection(input.parseResultId(), input.parseVersion(), null,
                List.of(), List.of(), List.of());
        return new Projection(input.parseResultId(), input.parseVersion(), attempt,
                attempts.findCandidates(attempt.attemptId()),
                attempts.findRejections(attempt.attemptId()),
                attempts.findModelCalls(attempt.attemptId()));
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(BusinessErrorCode.NOT_FOUND, message);
    }

    public record Projection(UUID currentParseResultId, int currentParseVersion,
            ContractFactExtractionAttempt attempt, List<ContractFactExtractionCandidate> candidates,
            List<ContractFactExtractionRejection> rejections,
            List<ContractModelCallAudit> modelCalls) {
        public Projection {
            candidates = List.copyOf(candidates);
            rejections = List.copyOf(rejections);
            modelCalls = List.copyOf(modelCalls);
        }
    }
}
