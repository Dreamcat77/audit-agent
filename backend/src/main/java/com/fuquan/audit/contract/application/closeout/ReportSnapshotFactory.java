package com.fuquan.audit.contract.application.closeout;

import com.fuquan.audit.contract.application.closeout.port.ContractReviewCloseoutRepository;
import com.fuquan.audit.contract.application.reviewrun.*;
import com.fuquan.audit.contract.domain.closeout.ContractReviewFindingAcknowledgement;
import com.fuquan.audit.contract.domain.reviewrun.*;
import com.fuquan.audit.review.application.port.FindingAcknowledgementReadRepository;
import com.fuquan.audit.review.domain.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Captures exact durable facts once; replay renders the saved JSON, never today's acknowledgements. */
@Component
public class ReportSnapshotFactory {
    public static final int SCHEMA = 4;
    private final ReviewStepOutputReadService outputs;
    private final FrozenReviewInputLoader frozen;
    private final ContractReviewCloseoutRepository repository;
    private final FindingAcknowledgementReadRepository f02Acknowledgements;
    public ReportSnapshotFactory(ReviewStepOutputReadService outputs, FrozenReviewInputLoader frozen,
            ContractReviewCloseoutRepository repository, FindingAcknowledgementReadRepository f02Acknowledgements) {
        this.outputs = outputs; this.frozen = frozen; this.repository = repository; this.f02Acknowledgements = f02Acknowledgements;
    }
    public Snapshot freeze(UUID project, UUID contract, UUID runId) {
        var output = outputs.getRun(project, contract, runId);
        var run = output.manifest().run();
        var statuses = output.steps().stream().map(s -> s.step().status()).toList();
        var stepTypes = output.steps().stream().map(s -> s.step().stepType()).collect(Collectors.toSet());
        boolean validManifest = output.steps().size() == ContractReviewStepType.values().length
                && output.steps().stream().allMatch(s -> s.step().required())
                && stepTypes.equals(EnumSet.allOf(ContractReviewStepType.class));
        boolean terminalSnapshot = validManifest && switch (run.status()) {
            case COMPLETED -> statuses.size() == ContractReviewStepType.values().length
                    && statuses.stream().allMatch(s -> s == ContractReviewStepStatus.COMPLETED);
            case FAILED -> statuses.size() == ContractReviewStepType.values().length
                    && statuses.contains(ContractReviewStepStatus.FAILED)
                    && statuses.stream().allMatch(s -> s == ContractReviewStepStatus.COMPLETED || s == ContractReviewStepStatus.FAILED);
            case UNKNOWN -> statuses.size() == ContractReviewStepType.values().length
                    && statuses.contains(ContractReviewStepStatus.UNKNOWN);
            case PARTIALLY_COMPLETED -> statuses.size() == ContractReviewStepType.values().length
                    && statuses.contains(ContractReviewStepStatus.COMPLETED)
                    && statuses.stream().anyMatch(s -> s != ContractReviewStepStatus.COMPLETED);
            default -> false;
        };
        if (!terminalSnapshot) throw CloseoutOwnership.conflict();
        var input = frozen.load(project, contract, runId);
        if (!input.run().inputSnapshotIdentity().equals(run.inputSnapshotIdentity())) throw CloseoutOwnership.conflict();
        List<Map<String, Object>> findingManifest = new ArrayList<>(), acknowledgements = new ArrayList<>();
        var nonF02 = unique(repository.findAcknowledgementsByRun(runId), ContractReviewFindingAcknowledgement::findingId);
        Set<UUID> seen = new HashSet<>();
        for (var part : output.steps().stream().sorted(Comparator.comparingInt(s -> s.step().stepType().ordinal())).toList()) {
            var step = part.step();
            var entry = new TreeMap<String, Object>();
            entry.put("stepType", step.stepType()); entry.put("stepOrder", step.stepType().ordinal());
            entry.put("status", step.status());
            entry.put("stepId", step.stepId()); entry.put("policyVersion", step.stepPolicyVersion());
            entry.put("attemptNo", step.currentAttemptNo());
            if (step.stepType() != ContractReviewStepType.MATERIAL_VALIDATION) {
                var attempt = part.attempts().stream().filter(a -> a.attemptNo() == step.currentAttemptNo()).findFirst();
                if (step.status() == ContractReviewStepStatus.COMPLETED) {
                    var completed = attempt.filter(a -> a.status() == ContractReviewStepStatus.COMPLETED)
                            .orElseThrow(CloseoutOwnership::conflict);
                    entry.put("attempt", completed);
                    if (part.findings().stream().anyMatch(f -> !f.attemptId().equals(completed.attemptId())))
                        throw CloseoutOwnership.conflict();
                } else if (attempt.isPresent()) {
                    entry.put("attempt", attempt.get());
                }
            }
            var findings = part.findings().stream().sorted(Comparator.comparingInt(ContractReviewFinding::ordinal)
                    .thenComparing(f -> f.findingId().toString())).toList();
            var evidence = part.evidence().stream().sorted(Comparator.comparing((ContractReviewEvidence e) -> e.findingId().toString())
                    .thenComparingInt(ContractReviewEvidence::ordinal).thenComparing(e -> e.evidenceId().toString())).toList();
            evidence.forEach(e -> verifyEvidence(input, e));
            entry.put("findings", findings); entry.put("evidence", evidence);
            if (step.stepType() == ContractReviewStepType.SEMANTIC_AI && output.monetaryObservation() != null)
                entry.put("monetaryObservation", output.monetaryObservation());
            entry.put("typedComparisons", part.typedComparisons());
            var checks = part.auditCheckResults().stream().sorted(Comparator.comparing(AuditCheckResult::ruleCode)
                    .thenComparing(AuditCheckResult::itemKey, Comparator.nullsFirst(String::compareTo))
                    .thenComparing(c -> c.id().toString())).toList();
            entry.put("checks", checks); entry.put("auditTask", part.auditTask());
            if (part.auditTask() != null) {
                var f02 = unique(f02Acknowledgements.findByTaskId(part.auditTask().id()), FindingAcknowledgement::checkResultId);
                if (!checks.stream().map(AuditCheckResult::id).collect(Collectors.toSet()).containsAll(f02.keySet()))
                    throw CloseoutOwnership.conflict();
                for (var check : checks) {
                    var ack = f02.get(check.id());
                    if (ack != null && ack.ruleVersion() != check.ruleVersion()) throw CloseoutOwnership.conflict();
                    acknowledgements.add(ackEntry("F02_CHECK_RESULT", check.id(), check.ruleVersion(), ack));
                }
            }
            for (var finding : step.stepType() == ContractReviewStepType.F02_DETERMINISTIC
                    ? List.<ContractReviewFinding>of() : findings) {
                seen.add(finding.findingId()); var ack = nonF02.get(finding.findingId());
                if (ack != null && (!ack.projectId().equals(project) || !ack.contractId().equals(contract)
                        || !ack.reviewRunId().equals(runId) || !ack.reviewStepId().equals(finding.stepId())
                        || !ack.attemptId().equals(finding.attemptId()) || ack.findingVersionNo() != finding.versionNo()))
                    throw CloseoutOwnership.conflict();
                var item = ackEntry("CONTRACT_REVIEW_FINDING", finding.findingId(), finding.versionNo(), ack);
                item.put("handlingNoteHash", ack == null || ack.handlingNote() == null ? "NONE" : CloseoutCanonical.sha256(ack.handlingNote()));
                acknowledgements.add(item);
            }
            findingManifest.add(entry);
        }
        if (!seen.containsAll(nonF02.keySet())) throw CloseoutOwnership.conflict();
        var body = new TreeMap<String, Object>();
        body.put("schemaVersion", SCHEMA); body.put("inputIdentity", run.inputSnapshotIdentity());
        body.put("contractName", input.frozenContract().contractName());
        body.put("businessProjection", BusinessReviewProjection.from(output));
        body.put("findings", findingManifest); body.put("acknowledgements", acknowledgements);
        body.put("materials", output.manifest().materials().stream().sorted(Comparator.comparingInt(ContractReviewRunMaterial::ordinal)).toList());
        String json = CloseoutCanonical.json(body);
        if (json.length() > 4_000_000) throw CloseoutOwnership.conflict();
        return new Snapshot(json, run.inputSnapshotIdentity(), CloseoutCanonical.sha256(CloseoutCanonical.json(findingManifest)),
                CloseoutCanonical.sha256(CloseoutCanonical.json(acknowledgements)));
    }
    private static Map<String, Object> ackEntry(String source, UUID id, long version, Object ack) {
        var entry = new TreeMap<String, Object>();
        entry.put("source", source); entry.put("findingId", id); entry.put("findingVersion", version);
        entry.put("state", ack == null ? "NONE" : "ACKNOWLEDGED"); entry.put("fact", ack); return entry;
    }
    private static <T> Map<UUID,T> unique(List<T> rows, Function<T,UUID> key) {
        Map<UUID,T> result = new HashMap<>();
        for (T row : rows) if (result.putIfAbsent(key.apply(row), row) != null) throw CloseoutOwnership.conflict();
        return result;
    }
    private static void verifyEvidence(FrozenReviewInput input, ContractReviewEvidence evidence) {
        var material = input.materials().stream().filter(m -> m.fileId().equals(evidence.fileId())
                && m.parseResultId().equals(evidence.parseResultId())).findFirst().orElseThrow(CloseoutOwnership::conflict);
        if (!evidence.locatorAvailable()) return;
        String text = material.canonicalText();
        if (evidence.endOffset() > text.codePointCount(0, text.length())) throw CloseoutOwnership.conflict();
        String excerpt = text.substring(text.offsetByCodePoints(0, evidence.startOffset()), text.offsetByCodePoints(0, evidence.endOffset()));
        if (!CloseoutCanonical.sha256(excerpt).equals(evidence.excerptSha256())
                || !excerpt.equals(evidence.boundedExcerpt())) throw CloseoutOwnership.conflict();
    }
    public record Snapshot(String json, String inputIdentity, String findingIdentity, String acknowledgementIdentity) { }
}
