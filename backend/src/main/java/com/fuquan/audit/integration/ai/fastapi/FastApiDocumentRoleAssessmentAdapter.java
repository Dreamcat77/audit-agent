package com.fuquan.audit.integration.ai.fastapi;

import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.contract.application.project.role.DocumentRoleAssessmentException;
import com.fuquan.audit.contract.application.project.role.DocumentRoleAssessmentException.Reason;
import com.fuquan.audit.contract.application.project.role.DocumentRoleAssessmentPort;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleModelProvenance;
import com.fuquan.audit.integration.http.FastApiContractPaths;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@Profile({"local-dev & local-ai", "dev29", "customer"})
public class FastApiDocumentRoleAssessmentAdapter implements DocumentRoleAssessmentPort {
    private static final Pattern SAFE_REASON = Pattern.compile("^[A-Z][A-Z0-9_]{0,99}$");
    private static final String PROVIDER = "OPENAI_COMPATIBLE";
    private static final String REVISION = "NOT_DECLARED_BY_PROVIDER";
    private static final String PROMPT = "document-role-prompt-v1";
    private static final String SCHEMA = "document-role-v1";

    private final RestClient restClient;
    private final ProjectFileRoleModelProvenance provenance;

    public FastApiDocumentRoleAssessmentAdapter(@Qualifier("aiRestClient") RestClient restClient,
            @Value("${audit.ai.document-role.model-name:${AUDIT_MODEL_NAME:configured-model}}")
            String modelName) {
        this.restClient = restClient;
        this.provenance = new ProjectFileRoleModelProvenance(
                PROVIDER, modelName, REVISION, PROMPT, SCHEMA);
    }

    @Override
    public ProjectFileRoleModelProvenance configuredProvenance() {
        return provenance;
    }

    @Override
    public Result assess(Request request) {
        validateRequest(request);
        try {
            RestClient.RequestBodySpec http = restClient.post().uri(FastApiContractPaths.DOCUMENT_ROLE);
            String requestId = MDC.get(RequestIdFilter.MDC_KEY);
            if (requestId != null && !requestId.isBlank()) {
                http.header(RequestIdFilter.HEADER_NAME, requestId);
            }
            Envelope envelope = http.contentType(MediaType.APPLICATION_JSON)
                    .body(wire(request)).retrieve().body(Envelope.class);
            return map(request, envelope);
        } catch (DocumentRoleAssessmentException failure) {
            throw failure;
        } catch (ResourceAccessException failure) {
            Throwable cause = failure.getCause();
            Reason reason = cause instanceof SocketTimeoutException ? Reason.TIMEOUT : Reason.UNAVAILABLE;
            throw new DocumentRoleAssessmentException(reason, failure);
        } catch (RestClientResponseException failure) {
            Reason reason = failure.getStatusCode().value() == 504 ? Reason.TIMEOUT : Reason.UNAVAILABLE;
            throw new DocumentRoleAssessmentException(reason, failure);
        } catch (RestClientException | IllegalArgumentException failure) {
            throw new DocumentRoleAssessmentException(Reason.INVALID_RESPONSE, failure);
        }
    }

    private Result map(Request request, Envelope envelope) {
        if (envelope == null || envelope.code() == null || envelope.code() != 200 || envelope.data() == null) {
            throw invalid();
        }
        Data data = envelope.data();
        ProjectFileRoleModelProvenance returned;
        try {
            returned = new ProjectFileRoleModelProvenance(data.modelProvider(), data.modelName(),
                    data.modelRevision(), data.promptVersion(), data.schemaVersion());
        } catch (RuntimeException invalid) {
            throw new DocumentRoleAssessmentException(Reason.INVALID_RESPONSE, invalid);
        }
        if (!provenance.equals(returned) || data.proposedRole() == null || data.confidence() == null
                || outsideConfidence(data.confidence()) || data.candidateRoles() == null
                || data.reasonCodes() == null || data.evidenceSegmentIds() == null
                || data.reasonCodes().stream().anyMatch(code -> code == null || !SAFE_REASON.matcher(code).matches())) {
            throw invalid();
        }
        Set<String> allowedSegments = new HashSet<>();
        request.segments().forEach(segment -> allowedSegments.add(segment.segmentId()));
        if (!allowedSegments.containsAll(data.evidenceSegmentIds())) throw invalid();
        List<Candidate> candidates = data.candidateRoles().stream().map(value -> {
            if (value == null || value.role() == null || value.role() == DocumentRole.UNKNOWN
                    || value.confidence() == null || outsideConfidence(value.confidence())
                    || value.reasonCode() == null || !SAFE_REASON.matcher(value.reasonCode()).matches()) {
                throw invalid();
            }
            return new Candidate(value.role(), value.confidence(), value.reasonCode());
        }).toList();
        if (data.proposedRole() == DocumentRole.UNKNOWN) {
            if (data.confidence().compareTo(BigDecimal.ZERO) != 0 || !candidates.isEmpty()
                    || !data.evidenceSegmentIds().isEmpty()) throw invalid();
        } else if (candidates.isEmpty() || candidates.get(0).role() != data.proposedRole()
                || candidates.get(0).confidence().compareTo(data.confidence()) != 0
                || data.evidenceSegmentIds().isEmpty()) {
            throw invalid();
        }
        return new Result(data.proposedRole(), data.confidence(), candidates,
                data.reasonCodes(), data.evidenceSegmentIds(), returned);
    }

    private static void validateRequest(Request request) {
        if (request == null || request.fileId() == null || request.parseResultId() == null
                || request.parseVersion() <= 0 || request.canonicalTextSha256() == null
                || !request.canonicalTextSha256().matches("[0-9a-f]{64}")
                || request.segments() == null || request.segments().isEmpty()) {
            throw invalid();
        }
        Set<String> ids = new HashSet<>();
        for (Segment segment : request.segments()) {
            String locator = "parse-result:%s:canonical-codepoint:%d-%d".formatted(
                    request.parseResultId(), segment.charStart(), segment.charEnd());
            if (segment.segmentId() == null || !ids.add(segment.segmentId())
                    || segment.charStart() < 0 || segment.charEnd() <= segment.charStart()
                    || !locator.equals(segment.sourceLocator())
                    || segment.textSha256() == null || !segment.textSha256().matches("[0-9a-f]{64}")
                    || segment.text() == null || segment.text().isBlank()) throw invalid();
        }
    }

    private static WireRequest wire(Request request) {
        return new WireRequest(request.fileId(), request.parseResultId(), request.parseVersion(),
                request.canonicalTextSha256(), request.segments().stream()
                .map(value -> new WireSegment(value.segmentId(), value.sourceLocator(),
                        value.textSha256(), value.text())).toList());
    }

    private static boolean outsideConfidence(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0;
    }

    private static DocumentRoleAssessmentException invalid() {
        return new DocumentRoleAssessmentException(Reason.INVALID_RESPONSE,
                new IllegalStateException("invalid downstream document role response"));
    }

    private record WireRequest(java.util.UUID fileId, java.util.UUID parseResultId, int parseVersion,
            String canonicalTextSha256, List<WireSegment> segments) { }
    private record WireSegment(String segmentId, String sourceLocator, String textSha256, String text) { }
    private record Envelope(Integer code, String message, Data data) { }
    private record Data(DocumentRole proposedRole, BigDecimal confidence, List<CandidateData> candidateRoles,
            List<String> reasonCodes, List<String> evidenceSegmentIds, String schemaVersion,
            String promptVersion, String modelProvider, String modelName, String modelRevision) { }
    private record CandidateData(DocumentRole role, BigDecimal confidence, String reasonCode) { }
}
