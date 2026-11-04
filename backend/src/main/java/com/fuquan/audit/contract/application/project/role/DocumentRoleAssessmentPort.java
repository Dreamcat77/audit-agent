package com.fuquan.audit.contract.application.project.role;

import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectFileRoleModelProvenance;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Typed boundary for a non-authoritative model proposal over immutable parse segments. */
public interface DocumentRoleAssessmentPort {
    ProjectFileRoleModelProvenance configuredProvenance();

    Result assess(Request request);

    record Request(UUID fileId, UUID parseResultId, int parseVersion,
                   String canonicalTextSha256, List<Segment> segments) {
        public Request {
            segments = List.copyOf(segments);
        }
    }

    record Segment(String segmentId, String sourceLocator, String textSha256,
                   int charStart, int charEnd, String text) { }

    record Candidate(DocumentRole role, BigDecimal confidence, String reasonCode) { }

    record Result(DocumentRole proposedRole, BigDecimal confidence,
                  List<Candidate> candidateRoles, List<String> reasonCodes,
                  List<String> evidenceSegmentIds,
                  ProjectFileRoleModelProvenance provenance) {
        public Result {
            candidateRoles = List.copyOf(candidateRoles);
            reasonCodes = List.copyOf(reasonCodes);
            evidenceSegmentIds = List.copyOf(evidenceSegmentIds);
        }
    }
}
