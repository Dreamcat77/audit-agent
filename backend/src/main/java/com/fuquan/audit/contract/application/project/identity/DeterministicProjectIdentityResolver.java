package com.fuquan.audit.contract.application.project.identity;

import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityAssessment;
import com.fuquan.audit.contract.domain.project.ProjectFileIdentityEvidence;
import com.fuquan.audit.contract.domain.project.ProjectFileParseResult;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentSource;
import com.fuquan.audit.contract.domain.project.ProjectIdentityAssessmentStatus;
import com.fuquan.audit.contract.domain.project.ProjectIdentityComparisonOutcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Direct exact comparisons against confirmed Project metadata, never automatic Project ownership. */
@Component
public class DeterministicProjectIdentityResolver {
    public static final String ASSESSMENT_VERSION = "identity-assessment-v1";
    public static final String POLICY_VERSION = "human-same-only-v1";

    public IdentityAssessmentCalculation resolve(Project project, ProjectFile file,
            ProjectFileParseResult parse, List<ExtractionResult> facts,
            UUID assessmentId, String inputFingerprint, Instant at) {
        Objects.requireNonNull(project);
        Objects.requireNonNull(file);
        Objects.requireNonNull(parse);
        Objects.requireNonNull(facts);
        if (!project.id().equals(file.projectId()) || !project.id().equals(parse.projectId())
                || !file.id().equals(parse.fileId()) || !file.sha256().equals(parse.fileSha256()))
            throw new IllegalArgumentException("identity source ownership binding mismatch");
        for (var fact : facts) {
            if (!fact.projectId().equals(project.id()) || !fact.sourceFileId().equals(file.id())
                    || !parse.parseResultId().equals(fact.parseResultId()))
                throw new IllegalArgumentException("identity fact parse binding mismatch");
        }
        List<ProjectFileIdentityEvidence> evidence = new ArrayList<>();
        boolean mismatch = false, match = false;
        String reason = null;
        if (!project.metadataConfirmed()) {
            reason = "PROJECT_METADATA_UNCONFIRMED";
        } else if (ProjectIdentityNormalizer.projectNo(project.projectNo()) == null
                && comparableProjectName(project.name()) == null) {
            reason = "PROJECT_IDENTITY_METADATA_MISSING";
        } else {
            for (var field : List.of("PROJECT_NO", "PROJECT_NAME")) {
                String projectRaw = "PROJECT_NO".equals(field) ? project.projectNo()
                        : comparableProjectName(project.name());
                String projectValue = normalized(field, projectRaw);
                List<ExtractionResult> typed = facts.stream().filter(value -> field.equals(value.factType())
                                && normalized(field, value.valueText()) != null)
                        .sorted(Comparator.comparing(ExtractionResult::extractionKey)
                                .thenComparing(value -> value.id().toString())).toList();
                if (typed.isEmpty()) {
                    evidence.add(evidence(project, file, parse, assessmentId, field, null,
                            projectRaw, projectValue, null, null, at));
                } else {
                    for (var fact : typed) {
                        String fileValue = normalized(field, fact.valueText());
                        ProjectFileIdentityEvidence comparison = evidence(project, file, parse, assessmentId,
                                field, fact, projectRaw, projectValue, fact.valueText(), fileValue, at);
                        evidence.add(comparison);
                        mismatch |= comparison.comparisonOutcome() == ProjectIdentityComparisonOutcome.MISMATCH;
                        match |= comparison.comparisonOutcome() == ProjectIdentityComparisonOutcome.MATCH;
                    }
                }
            }
        }
        ProjectIdentityAssessmentStatus status = mismatch ? ProjectIdentityAssessmentStatus.CONFLICT
                : match ? ProjectIdentityAssessmentStatus.LIKELY_SAME_PROJECT
                : ProjectIdentityAssessmentStatus.INSUFFICIENT;
        if (status == ProjectIdentityAssessmentStatus.INSUFFICIENT && reason == null)
            reason = "NO_COMPARABLE_PROJECT_FACT";
        var assessment = new ProjectFileIdentityAssessment(assessmentId, project.id(), project.versionNo(),
                file.id(), file.sha256(), parse.parseResultId(), parse.parseVersion(),
                ProjectIdentityAssessmentSource.DETERMINISTIC, ASSESSMENT_VERSION, POLICY_VERSION,
                status, inputFingerprint, reason, at, at, 0);
        return new IdentityAssessmentCalculation(assessment, evidence);
    }

    private static ProjectFileIdentityEvidence evidence(Project project, ProjectFile file,
            ProjectFileParseResult parse, UUID assessmentId, String field, ExtractionResult fact,
            String projectRaw, String projectValue, String fileRaw, String fileValue, Instant at) {
        ProjectIdentityComparisonOutcome outcome = fileValue == null
                ? ProjectIdentityComparisonOutcome.MISSING_FILE_VALUE
                : projectValue == null ? ProjectIdentityComparisonOutcome.MISSING_PROJECT_VALUE
                : projectValue.equals(fileValue) ? ProjectIdentityComparisonOutcome.MATCH
                : ProjectIdentityComparisonOutcome.MISMATCH;
        return new ProjectFileIdentityEvidence(UUID.randomUUID(), assessmentId, project.id(),
                project.versionNo(), file.id(), file.sha256(), parse.parseResultId(), parse.parseVersion(),
                field, fact == null ? null : fact.id(),
                projectValue == null ? "MISSING_PROJECT_METADATA" : "PROJECT_METADATA",
                fact == null ? "MISSING_FILE_FACT" : "PARSE_FACT",
                hash(projectValue), hash(fileValue), bounded(projectRaw, 160), bounded(fileRaw, 160),
                outcome, "STRONG", fact == null ? null : fact.sourceLocator(), at);
    }

    private static String normalized(String field, String raw) {
        return "PROJECT_NO".equals(field) ? ProjectIdentityNormalizer.projectNo(raw)
                : ProjectIdentityNormalizer.projectName(raw);
    }
    private static String comparableProjectName(String value) {
        return com.fuquan.audit.contract.application.project.AutomaticProjectMetadataService
                .isGeneratedName(value) ? null : value;
    }
    private static String bounded(String value, int max) {
        return value == null ? null : value.length() <= max ? value : value.substring(0, max);
    }
    private static String hash(String value) {
        if (value == null) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by Java 17", failure);
        }
    }
}
