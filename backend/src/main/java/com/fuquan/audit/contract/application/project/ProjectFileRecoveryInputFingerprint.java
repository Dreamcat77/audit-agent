package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.identity.EffectiveProjectIdentity;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.application.project.role.ProjectFileRoleProjection;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.security.application.AuthContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Canonical identity of every durable input authorized for one progression recovery action. */
@Component
public class ProjectFileRecoveryInputFingerprint {
    private static final String VERSION = "project-file-recovery-input-v1";

    private final ProjectRepository projects;
    private final ProjectFileParseResultRepository parses;
    private final AuthContext auth;

    public ProjectFileRecoveryInputFingerprint(ProjectRepository projects,
            ProjectFileParseResultRepository parses, AuthContext auth) {
        this.projects = projects;
        this.parses = parses;
        this.auth = auth;
    }

    public String create(ProjectFile file, ProjectFileRoleProjection role,
            EffectiveProjectIdentity identity, String blocker, String action) {
        String actorId = auth.currentUser()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED))
                .userId();
        Project project = projects.findById(file.projectId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project not found"));
        ProjectFileParseResult parse = parses.findLatestByFileId(file.id())
                .filter(value -> value.projectId().equals(file.projectId()))
                .filter(value -> value.fileId().equals(file.id()))
                .filter(value -> value.fileSha256().equals(file.sha256()))
                .filter(value -> value.parseStatus() == ProjectFileParseStatus.PARSED)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.CONFLICT,
                        "current immutable parse result is required for progression recovery"));
        return canonicalFingerprint(actorId, project, file, parse, role, identity, blocker, action);
    }

    /** Read projection seam: a legacy file without an immutable parse stays readable but cannot be resumed. */
    public Optional<String> createIfCurrentParseAvailable(ProjectFile file, ProjectFileRoleProjection role,
            EffectiveProjectIdentity identity, String blocker, String action) {
        String actorId = auth.currentUser()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED))
                .userId();
        Project project = projects.findById(file.projectId())
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project not found"));
        return parses.findLatestByFileId(file.id())
                .filter(value -> value.projectId().equals(file.projectId()))
                .filter(value -> value.fileId().equals(file.id()))
                .filter(value -> value.fileSha256().equals(file.sha256()))
                .filter(value -> value.parseStatus() == ProjectFileParseStatus.PARSED)
                .map(parse -> canonicalFingerprint(actorId, project, file, parse, role, identity,
                        blocker, action));
    }

    static String canonicalFingerprint(String actorId, Project project, ProjectFile file,
            ProjectFileParseResult parse, ProjectFileRoleProjection role,
            EffectiveProjectIdentity identity, String blocker, String action) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(parse, "parse must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(identity, "identity must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, VERSION);
            add(digest, actorId);
            add(digest, project.id());
            add(digest, project.versionNo());
            add(digest, file.id());
            add(digest, file.sha256());
            add(digest, parse.parseResultId());
            add(digest, parse.parseVersion());
            add(digest, parse.canonicalTextSha256());
            add(digest, role.parseResultId());
            add(digest, role.parseVersion());
            add(digest, role.roleAssessmentId());
            add(digest, role.roleExecutionStatus());
            add(digest, role.roleDecisionStatus());
            add(digest, role.effectiveRole());
            add(digest, role.effectiveRoleSource());
            add(digest, role.roleDecisionId());
            add(digest, role.roleDecisionVersion());
            add(digest, identity.assessmentId());
            add(digest, identity.assessmentVersion());
            add(digest, identity.assessmentStatus());
            add(digest, identity.decisionStatus());
            add(digest, identity.decisionId());
            add(digest, identity.decisionVersion());
            add(digest, identity.identityCurrent());
            add(digest, identity.recoveryReason());
            add(digest, blocker);
            add(digest, action);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by Java 17", failure);
        }
    }

    private static void add(MessageDigest digest, Object raw) {
        String value = raw == null ? "<null>" : raw.toString();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
