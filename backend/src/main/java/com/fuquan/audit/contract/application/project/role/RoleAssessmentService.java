package com.fuquan.audit.contract.application.project.role;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import com.fuquan.audit.contract.application.project.LegacyProjectFileParseResultMaterializer;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.domain.project.DeterministicDocumentRoleResult;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileParseStatus;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RoleAssessmentService {
    public static final String ASSESSMENT_VERSION = "deterministic-role-assessment-v1";
    public static final String ROLE_POLICY_VERSION = "document-role-policy-v1";
    private static final String FINGERPRINT_VERSION = "role-assessment-fingerprint-v1";
    private static final Pattern SAFE_IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final ProjectFileRepository files;
    private final ProjectFileParseResultRepository parseResults;
    private final LegacyProjectFileParseResultMaterializer legacyMaterializer;
    private final RoleAssessmentTransactionCoordinator transactions;
    private final AuthContext authContext;
    private final Clock clock;
    private final FlowDebugLogger flow;
    private final Function<ProjectFileParseResult, DeterministicDocumentRoleResult> computation;

    @Autowired
    public RoleAssessmentService(ProjectFileRepository files, ProjectFileParseResultRepository parseResults,
            LegacyProjectFileParseResultMaterializer legacyMaterializer,
            RoleAssessmentTransactionCoordinator transactions, AuthContext authContext,
            Clock clock, FlowDebugLogger flow) {
        this(files, parseResults, legacyMaterializer, transactions, authContext, clock, flow,
                RoleAssessmentService::computeDeterministically);
    }

    RoleAssessmentService(ProjectFileRepository files, ProjectFileParseResultRepository parseResults,
            LegacyProjectFileParseResultMaterializer legacyMaterializer,
            RoleAssessmentTransactionCoordinator transactions, AuthContext authContext,
            Clock clock, FlowDebugLogger flow,
            Function<ProjectFileParseResult, DeterministicDocumentRoleResult> computation) {
        this.files = files;
        this.parseResults = parseResults;
        this.legacyMaterializer = legacyMaterializer;
        this.transactions = transactions;
        this.authContext = authContext;
        this.clock = clock;
        this.flow = flow;
        this.computation = Objects.requireNonNull(computation, "computation must not be null");
    }

    public RoleAssessmentResult assess(RoleAssessmentCommand command) {
        return assess(command, currentUser());
    }

    public RoleAssessmentResult assess(RoleAssessmentCommand command, CurrentUser actor) {
        validateKey(command.idempotencyKey());
        ProjectFile file = requireFile(command.projectId(), command.fileId());
        ProjectFileParseResult parse = parseResults.findLatestByFileId(command.fileId())
                .orElseGet(() -> legacyMaterializer.materialize(command.projectId(), command.fileId()));
        requireInput(file, parse);
        String fingerprint = fingerprint(actor, command, parse);
        Instant startedAt = clock.instant();
        RoleAssessmentTransactionCoordinator.Start start = transactions.start(
                command, actor, parse, fingerprint, UUID.randomUUID(), startedAt);
        if (start.isReplay()) return start.replay();

        RoleAssessmentTransactionCoordinator.Start running = transactions.markRunning(start, clock.instant());
        flow.log("ROLE_ASSESSMENT_COMPUTE_START", "CONTRACT", "APPLICATION", "deterministicRoleAssessment",
                Map.of("assessmentId", running.assessment().assessmentId()));
        DeterministicDocumentRoleResult result;
        try {
            result = computation.apply(parse);
        } catch (RuntimeException failure) {
            closeFailure(running, actor, command, failure);
            throw failure;
        }
        try {
            return transactions.complete(running, result, actor, command, clock.instant());
        } catch (RuntimeException uncertain) {
            try {
                return transactions.reconcileCompleteFailure(running, actor, command, uncertain, clock.instant());
            } catch (RuntimeException reconciliationFailure) {
                uncertain.addSuppressed(reconciliationFailure);
                throw uncertain;
            }
        }
    }

    public RoleAssessmentResult get(UUID projectId, UUID fileId, UUID assessmentId) {
        currentUser();
        requireFile(projectId, fileId);
        return transactions.reopen(projectId, fileId, assessmentId, false);
    }

    private void closeFailure(RoleAssessmentTransactionCoordinator.Start running, CurrentUser actor,
                              RoleAssessmentCommand command, RuntimeException original) {
        try {
            transactions.fail(running, actor, command, original, clock.instant());
        } catch (RuntimeException closure) {
            original.addSuppressed(closure);
        }
    }

    private ProjectFile requireFile(UUID projectId, UUID fileId) {
        return files.findByProjectIdAndId(projectId, fileId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND, "project file not found"));
    }

    private static void requireInput(ProjectFile file, ProjectFileParseResult parse) {
        if (file.ingestionStatus() != ProjectFileIngestionStatus.AVAILABLE
                || file.parseStatus() != ProjectFileParseStatus.PARSED
                || parse.parseStatus() != ProjectFileParseStatus.PARSED
                || !parse.projectId().equals(file.projectId()) || !parse.fileId().equals(file.id())
                || !parse.fileSha256().equals(file.sha256())
                || !Objects.equals(parse.canonicalText(), file.parsedText())
                || parse.canonicalTextSha256() == null) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "project file and immutable parse result are inconsistent");
        }
    }

    private CurrentUser currentUser() {
        return authContext.currentUser()
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED));
    }

    static String fingerprint(CurrentUser actor, RoleAssessmentCommand command, ProjectFileParseResult parse) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, FINGERPRINT_VERSION);
            add(digest, actor.userId());
            add(digest, command.projectId().toString());
            add(digest, command.fileId().toString());
            add(digest, parse.fileSha256());
            add(digest, parse.parseResultId().toString());
            add(digest, parse.canonicalTextSha256());
            add(digest, ASSESSMENT_VERSION);
            add(digest, ROLE_POLICY_VERSION);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by Java 17", failure);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static void validateKey(String key) {
        if (!SAFE_IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "invalid Idempotency-Key");
        }
    }

    private static DeterministicDocumentRoleResult computeDeterministically(ProjectFileParseResult parse) {
        return new DeterministicDocumentRoleResolver().resolve(new DocumentRoleSignalExtractor().extract(parse));
    }
}
