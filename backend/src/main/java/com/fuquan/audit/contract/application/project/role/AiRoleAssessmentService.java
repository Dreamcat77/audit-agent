package com.fuquan.audit.contract.application.project.role;

import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.application.project.port.ProjectFileParseResultRepository;
import com.fuquan.audit.contract.domain.project.DeterministicDocumentRoleResult;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.DocumentRoleCandidate;
import com.fuquan.audit.contract.domain.project.DocumentRoleSignal;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleAssessment;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AiRoleAssessmentService {
    public static final String ASSESSMENT_VERSION = "ai-role-assessment-v1";
    private static final BigDecimal MIN_DETERMINISTIC_CONFIDENCE = new BigDecimal("0.60");
    private static final int SEGMENT_CODEPOINTS = 1_000;
    private static final int MAX_SEGMENTS = 12;

    private final ProjectFileParseResultRepository parses;
    private final DocumentRoleAssessmentPort port;
    private final RoleAssessmentTransactionCoordinator transactions;
    private final AuthContext auth;
    private final Clock clock;

    public AiRoleAssessmentService(ProjectFileParseResultRepository parses, DocumentRoleAssessmentPort port,
            RoleAssessmentTransactionCoordinator transactions, AuthContext auth, Clock clock) {
        this.parses = parses;
        this.port = port;
        this.transactions = transactions;
        this.auth = auth;
        this.clock = clock;
    }

    public RoleAssessmentResult assessIfNeeded(RoleAssessmentResult deterministic,
            ProjectRequestMetadata metadata) {
        return auth.currentUser().map(actor -> assessIfNeeded(deterministic, metadata, actor))
                .orElseThrow(() -> new IllegalStateException("authenticated actor is required"));
    }

    public RoleAssessmentResult assessIfNeeded(RoleAssessmentResult deterministic,
            ProjectRequestMetadata metadata, CurrentUser actor) {
        if (!needsFallback(deterministic)) return deterministic;
        ProjectFileRoleAssessment prior = deterministic.assessment();
        ProjectFileParseResult parse = parses.findById(prior.parseResultId())
                .orElseThrow(() -> new IllegalStateException("AI role parse result is missing"));
        List<DocumentRoleAssessmentPort.Segment> segments = segments(parse);
        String key = "project-intake-role-ai:" + prior.assessmentId();
        RoleAssessmentCommand command = new RoleAssessmentCommand(
                prior.projectId(), prior.fileId(), key, metadata);
        String fingerprint = fingerprint(actor.userId(), prior, parse, port.configuredProvenance().toString());
        RoleAssessmentTransactionCoordinator.Start start = transactions.startAi(command, actor, parse,
                fingerprint, UUID.randomUUID(), prior.assessmentId(), port.configuredProvenance(), clock.instant());
        if (start.isReplay()) return start.replay();
        RoleAssessmentTransactionCoordinator.Start running = transactions.markRunning(start, clock.instant());
        try {
            DocumentRoleAssessmentPort.Result proposal = port.assess(new DocumentRoleAssessmentPort.Request(
                    prior.fileId(), parse.parseResultId(), parse.parseVersion(),
                    parse.canonicalTextSha256(), segments));
            if (!port.configuredProvenance().equals(proposal.provenance())) {
                throw new DocumentRoleAssessmentException(
                        DocumentRoleAssessmentException.Reason.INVALID_RESPONSE, null);
            }
            DeterministicDocumentRoleResult normalized = normalize(proposal, parse, segments);
            try {
                return transactions.completeAi(running, normalized, actor, command, clock.instant());
            } catch (RuntimeException uncertain) {
                return transactions.reconcileCompleteFailure(
                        running, actor, command, uncertain, clock.instant());
            }
        } catch (DocumentRoleAssessmentException safeFailure) {
            return transactions.failAi(running, actor, command, reason(safeFailure.reason()),
                    safeFailure, clock.instant());
        } catch (IllegalArgumentException invalid) {
            return transactions.failAi(running, actor, command, "AI_ROLE_INVALID_RESPONSE",
                    invalid, clock.instant());
        }
    }

    static boolean needsFallback(RoleAssessmentResult result) {
        ProjectFileRoleAssessment assessment = result.assessment();
        return assessment.proposedRole() == DocumentRole.UNKNOWN || assessment.conflict()
                || assessment.confidence().compareTo(MIN_DETERMINISTIC_CONFIDENCE) < 0;
    }

    private static DeterministicDocumentRoleResult normalize(DocumentRoleAssessmentPort.Result result,
            ProjectFileParseResult parse, List<DocumentRoleAssessmentPort.Segment> segments) {
        if (result.proposedRole() == null || result.confidence() == null
                || result.candidateRoles() == null || result.reasonCodes() == null
                || result.evidenceSegmentIds() == null || result.provenance() == null) {
            throw new IllegalArgumentException("incomplete AI document-role proposal");
        }
        Map<String, DocumentRoleAssessmentPort.Segment> byId = new LinkedHashMap<>();
        segments.forEach(segment -> byId.put(segment.segmentId(), segment));
        List<DocumentRoleAssessmentPort.Segment> selected = result.evidenceSegmentIds().stream()
                .map(id -> {
                    var value = byId.get(id);
                    if (value == null) throw new IllegalArgumentException("AI evidence segment is not owned");
                    return value;
                }).toList();
        if (result.proposedRole() == DocumentRole.UNKNOWN) {
            if (result.confidence().compareTo(BigDecimal.ZERO) != 0
                    || !result.candidateRoles().isEmpty() || !selected.isEmpty()) {
                throw new IllegalArgumentException("invalid UNKNOWN AI proposal");
            }
            return new DeterministicDocumentRoleResult(DocumentRole.UNKNOWN, BigDecimal.ZERO,
                    List.of(), false, List.of(), DeterministicDocumentRoleResult.DecisionStatus.NEEDS_CONFIRMATION);
        }
        if (selected.isEmpty()) throw new IllegalArgumentException("AI proposal requires evidence");
        List<DocumentRoleCandidate> candidates = new ArrayList<>();
        List<DocumentRoleSignal> allSignals = new ArrayList<>();
        for (DocumentRoleAssessmentPort.Candidate candidate : result.candidateRoles()) {
            if (candidate.role() == null || candidate.role() == DocumentRole.UNKNOWN
                    || candidate.confidence() == null || candidate.confidence().compareTo(BigDecimal.ZERO) <= 0
                    || candidate.reasonCode() == null || candidate.reasonCode().isBlank()) {
                throw new IllegalArgumentException("invalid AI candidate role");
            }
            List<DocumentRoleSignal> signals = selected.stream()
                    .map(segment -> signal(parse, segment, candidate.role(), candidate.reasonCode(),
                            candidate.confidence())).toList();
            allSignals.addAll(signals);
            candidates.add(new DocumentRoleCandidate(candidate.role(), candidate.confidence(),
                    candidate.confidence(), signals));
        }
        if (candidates.isEmpty() || candidates.get(0).role() != result.proposedRole()
                || candidates.get(0).confidence().compareTo(result.confidence()) != 0) {
            throw new IllegalArgumentException("AI leading proposal is inconsistent");
        }
        return new DeterministicDocumentRoleResult(result.proposedRole(), result.confidence(),
                candidates, false, allSignals,
                DeterministicDocumentRoleResult.DecisionStatus.NEEDS_CONFIRMATION);
    }

    private static DocumentRoleSignal signal(ProjectFileParseResult parse,
            DocumentRoleAssessmentPort.Segment segment, DocumentRole role,
            String reasonCode, BigDecimal weight) {
        String excerpt = bound(segment.text(), 160);
        return new DocumentRoleSignal(role, DocumentRoleSignal.SignalType.STRUCTURE,
                reasonCode.toLowerCase(java.util.Locale.ROOT), reasonCode, weight,
                parse.parseResultId(), segment.charStart(), segment.charEnd(), segment.sourceLocator(),
                segment.textSha256(), excerpt, "document-role-ai-v1");
    }

    private static List<DocumentRoleAssessmentPort.Segment> segments(ProjectFileParseResult parse) {
        String text = parse.canonicalText();
        int total = text.codePointCount(0, text.length());
        List<DocumentRoleAssessmentPort.Segment> result = new ArrayList<>();
        for (int start = 0, index = 1; start < total && index <= MAX_SEGMENTS;
                start += SEGMENT_CODEPOINTS, index++) {
            int end = Math.min(total, start + SEGMENT_CODEPOINTS);
            int utf16Start = text.offsetByCodePoints(0, start);
            int utf16End = text.offsetByCodePoints(0, end);
            String value = text.substring(utf16Start, utf16End);
            result.add(new DocumentRoleAssessmentPort.Segment("segment-%d".formatted(index),
                    "parse-result:%s:canonical-codepoint:%d-%d".formatted(parse.parseResultId(), start, end),
                    sha256(value), start, end, value));
        }
        return List.copyOf(result);
    }

    private static String reason(DocumentRoleAssessmentException.Reason reason) {
        return switch (reason) {
            case UNAVAILABLE -> "AI_ROLE_UNAVAILABLE";
            case TIMEOUT -> "AI_ROLE_TIMEOUT";
            case INVALID_RESPONSE -> "AI_ROLE_INVALID_RESPONSE";
        };
    }

    private static String fingerprint(String actor, ProjectFileRoleAssessment prior,
            ProjectFileParseResult parse, String provenance) {
        return sha256(String.join("|", actor, prior.assessmentId().toString(),
                parse.canonicalTextSha256(), provenance, ASSESSMENT_VERSION));
    }

    private static String bound(String value, int limit) {
        int count = value.codePointCount(0, value.length());
        return count <= limit ? value : value.substring(0, value.offsetByCodePoints(0, limit));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
