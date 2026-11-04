package com.fuquan.audit.contract.application.session;

import com.fuquan.audit.common.exception.*;
import com.fuquan.audit.contract.application.project.port.*;
import com.fuquan.audit.contract.application.project.role.*;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentityResolver;
import com.fuquan.audit.contract.application.reviewrun.port.*;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ReviewSessionProjectFileBinding;
import com.fuquan.audit.contract.domain.project.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** Reference-only readiness, inside the owning short write transaction. */
@Component
public class ProjectSessionReadiness {
    public record Material(ProjectFile file, ProjectFileParseResult parse, DocumentRole role,
            UUID roleDecisionId, UUID identityDecisionId) {
        ReviewSessionProjectFileBinding bind(UUID sessionId, int ordinal, String actor, Instant now, long selection) {
            return new ReviewSessionProjectFileBinding(UUID.randomUUID(), sessionId, file.projectId(), file.id(),
                    file.sha256(), parse.parseResultId(), parse.parseVersion(), parse.canonicalTextSha256(), role,
                    roleDecisionId, identityDecisionId, role.name() + ":" + ordinal, ordinal, true, actor, now, selection, 0);
        }
        boolean matches(ReviewSessionProjectFileBinding b) {
            return file.id().equals(b.projectFileId()) && file.projectId().equals(b.projectId())
                    && file.sha256().equals(b.fileSha256()) && parse.parseResultId().equals(b.parseResultId())
                    && parse.parseVersion() == b.parseVersion() && parse.canonicalTextSha256().equals(b.canonicalTextSha256())
                    && role == b.effectiveRole() && Objects.equals(roleDecisionId, b.roleDecisionId())
                    && identityDecisionId.equals(b.identityDecisionId());
        }
    }
    public record Ready(Contract contract, List<Material> materials) { public Ready { materials=List.copyOf(materials); } }
    private final ProjectRepository projects;
    private final ProjectContractSnapshotLockPort associations;
    private final ContractSnapshotLockPort contracts;
    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parses;
    private final EffectiveDocumentRoleResolver roles;
    private final EffectiveProjectIdentityResolver identities;
    public ProjectSessionReadiness(ProjectRepository projects, ProjectContractSnapshotLockPort associations,
            ContractSnapshotLockPort contracts, ProjectFileRepository files, ProjectFileParseResultRepository parses,
            EffectiveDocumentRoleResolver roles, EffectiveProjectIdentityResolver identities) {
        this.projects=projects; this.associations=associations; this.contracts=contracts;
        this.files=files; this.parses=parses; this.roles=roles; this.identities=identities;
    }
    public Ready resolve(UUID projectId, UUID contractId, List<UUID> fileIds) {
        validateSelection(fileIds);
        List<ProjectFile> selected=new ArrayList<>();
        for (UUID id : fileIds.stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
            selected.add(files.findByProjectIdAndIdForUpdate(projectId,id)
                    .filter(f -> f.projectId().equals(projectId) && f.id().equals(id)).orElseThrow(ProjectSessionReadiness::notFound));
        }
        Project project=projects.findByIdForUpdate(projectId).orElseThrow(ProjectSessionReadiness::notFound);
        associations.findByContractIdForUpdate(contractId).filter(a -> a.projectId().equals(projectId) && a.contractId().equals(contractId))
                .orElseThrow(ProjectSessionReadiness::notFound);
        Contract contract=contracts.findByIdForUpdate(contractId).orElseThrow(ProjectSessionReadiness::notFound);
        if (!project.metadataConfirmed()) throw conflict("Project metadata is not confirmed");
        List<Material> materials=new ArrayList<>();
        for (ProjectFile f : selected) {
            if (f.category()==ProjectFileCategory.PACKAGE || f.ingestionStatus()!=ProjectFileIngestionStatus.AVAILABLE
                    || f.parseStatus()!=ProjectFileParseStatus.PARSED) throw conflict("Material is not available and parsed");
            var p=parses.findLatestByFileId(f.id()).orElseThrow(() -> conflict("Immutable parse is unavailable"));
            if (!p.projectId().equals(projectId) || !p.fileId().equals(f.id()) || !p.fileSha256().equals(f.sha256())
                    || p.parseStatus()!=ProjectFileParseStatus.PARSED || !Objects.equals(p.canonicalText(),f.parsedText()))
                throw conflict("Immutable parse does not match material");
            var role=roles.resolve(f);
            if (role.effectiveRole()==DocumentRole.UNKNOWN || !p.parseResultId().equals(role.parseResultId())
                    || role.parseVersion()==null || role.parseVersion()!=p.parseVersion()
                    || (!role.source().isAuthoritativeDecision()
                        && !(role.source()==EffectiveDocumentRoleSource.LEGACY_EFFECTIVE && p.parseOrigin()==ProjectFileParseOrigin.LEGACY_IMPORTED)))
                throw conflict("Material role is not authoritative");
            var identity=identities.resolve(project,f);
            if (!identity.eligibleForProjectUse() || !identity.identityCurrent() || identity.excludedFromProjectUse())
                throw conflict("Material identity is not confirmed for Project");
            var decision=identity.currentHumanSameDecision();
            if (!decision.parseResultId().equals(p.parseResultId()) || decision.parseVersion()!=p.parseVersion()
                    || !decision.decisionId().equals(identity.decisionId())) throw conflict("Material identity is stale");
            materials.add(new Material(f,p,role.effectiveRole(),role.roleDecisionId(),decision.decisionId()));
        }
        return new Ready(contract,materials);
    }
    static void validateSelection(List<UUID> ids) {
        if (ids==null || ids.isEmpty() || ids.size()>100 || ids.stream().anyMatch(Objects::isNull)
                || new HashSet<>(ids).size()!=ids.size()) throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,"Invalid material selection");
    }
    private static BusinessException notFound() { return new BusinessException(BusinessErrorCode.NOT_FOUND); }
    private static BusinessException conflict(String message) { return new BusinessException(BusinessErrorCode.CONFLICT,message); }
}
