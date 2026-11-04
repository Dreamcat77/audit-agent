package com.fuquan.audit.contract.application;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.project.CandidateSupportAuthorityValidator;
import com.fuquan.audit.contract.application.project.ResolvedCandidateSupportAuthoritySet;
import com.fuquan.audit.contract.application.project.port.ContractCandidateRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateConfirmationRepository;
import com.fuquan.audit.contract.application.project.port.ContractCandidateSupportAuthorityRepository;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectContractQueryPort;
import com.fuquan.audit.contract.application.project.port.ProjectFileRepository;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewInput;
import com.fuquan.audit.contract.application.reviewrun.FrozenReviewMaterial;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractLineItem;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.contract.domain.project.CandidateAuthorityMode;
import com.fuquan.audit.contract.domain.project.CandidatePromotionState;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateConfirmation;
import com.fuquan.audit.contract.domain.project.ContractCandidateLineItem;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectContract;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.domain.project.ProjectFileIngestionStatus;
import com.fuquan.audit.review.domain.AuditCheckResult;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class FindingSourceProjectionService {
    private static final int MAX_PROJECTED_EXCERPT_CHARS = 240;
    private static final Set<String> SUPPORT_AWARD_FACTS = Set.of(
            "AWARD_AMOUNT", "AWARD_SUPPLIER", "AWARD_SUPPLIER_CREDIT_CODE");
    private static final List<String> LINE_ITEM_FACT_ORDER = List.of(
            "LINE_ITEM_KEY",
            "LINE_ITEM_NAME",
            "LINE_ITEM_QUANTITY",
            "LINE_ITEM_UNIT_PRICE",
            "LINE_ITEM_DECLARED_AMOUNT");
    private static final Pattern DOCX_TABLE_ROW_LOCATOR = Pattern.compile(
            "^(docx/table:[0-9]+/row:[0-9]+)/cell:[0-9]+(?:/.*)?$");

    private final ProjectContractQueryPort associations;
    private final ContractCandidateRepository candidates;
    private final ProjectFileRepository files;
    private final ExtractionResultRepository extractions;
    private final ContractCandidateSupportAuthorityRepository supports;
    private final CandidateSupportAuthorityValidator supportAuthority;
    private final ContractCandidateConfirmationRepository confirmations;

    /** Historical primary-only construction seam; Spring always injects the complete authority boundary. */
    public FindingSourceProjectionService(ProjectContractQueryPort associations,
            ContractCandidateRepository candidates, ProjectFileRepository files,
            ExtractionResultRepository extractions) {
        this(associations, candidates, files, extractions, null, null);
    }

    public FindingSourceProjectionService(ProjectContractQueryPort associations,
            ContractCandidateRepository candidates, ProjectFileRepository files,
            ExtractionResultRepository extractions, ContractCandidateSupportAuthorityRepository supports,
            CandidateSupportAuthorityValidator supportAuthority) {
        this(associations, candidates, files, extractions, supports, supportAuthority, null);
    }

    @Autowired
    public FindingSourceProjectionService(ProjectContractQueryPort associations,
            ContractCandidateRepository candidates, ProjectFileRepository files,
            ExtractionResultRepository extractions, ContractCandidateSupportAuthorityRepository supports,
            CandidateSupportAuthorityValidator supportAuthority,
            ContractCandidateConfirmationRepository confirmations) {
        this.associations = associations;
        this.candidates = candidates;
        this.files = files;
        this.extractions = extractions;
        this.supports = supports;
        this.supportAuthority = supportAuthority;
        this.confirmations = confirmations;
    }

    public FindingSourceProjection project(Contract contract, AuditCheckResult finding) {
        Objects.requireNonNull(contract, "contract must not be null");
        Objects.requireNonNull(finding, "finding must not be null");
        if (contract.versionNo() != 0) return unavailable("CONTRACT_VERSION_NOT_PROMOTION_SNAPSHOT");
        ProjectContract association = associations.findByContractId(contract.id()).orElse(null);
        if (association == null) return unavailable("PROJECT_CONTRACT_NOT_FOUND");
        ContractCandidate candidate = candidates.findByProjectIdAndId(
                association.projectId(), association.candidateId()).orElse(null);
        if (candidate == null || candidate.promotionState() != CandidatePromotionState.PROMOTED
                || !contract.id().equals(candidate.promotedContractId())) {
            return unavailable("CANDIDATE_PROMOTION_NOT_PROVEN");
        }
        if (files.findByProjectIdAndId(association.projectId(), candidate.sourceContractFileId())
                .filter(file -> file.ingestionStatus() == ProjectFileIngestionStatus.AVAILABLE).isEmpty()) {
            return unavailable("SOURCE_FILE_NOT_AVAILABLE");
        }
        ProjectionPlan plan = plan(contract, candidate, finding);
        if (plan == null) return unavailable("RULE_NOT_PROJECTABLE");
        if (plan.crossFile()) {
            if (supports == null) {
                if (candidate.supportAuthorityVersionNo() != null) return unavailable("SUPPORT_AUTHORITY_NOT_CURRENT");
            } else {
                var rows = supports.findCurrentByProjectIdAndCandidateId(candidate.projectId(), candidate.id());
                if (rows == null) return unavailable("SUPPORT_AUTHORITY_NOT_CURRENT");
                if (!rows.isEmpty() || candidate.supportAuthorityVersionNo() != null) {
                    try {
                        var current = supportAuthority.validateCurrent(candidate);
                        if (current.authorityMode() == CandidateAuthorityMode.EXPLICIT_SUPPORT_AUTHORITY) {
                            return projectExplicit(candidate, plan, current);
                        }
                    } catch (BusinessException failure) {
                        if (failure.errorCode() == BusinessErrorCode.CONFLICT
                                || failure.errorCode() == BusinessErrorCode.NOT_FOUND) {
                            return unavailable("SUPPORT_AUTHORITY_NOT_CURRENT");
                        }
                        throw failure;
                    }
                }
            }
        }
        if (!plan.matchesCandidate()) return unavailable("PROMOTION_VALUE_MISMATCH");
        List<ExtractionResult> evidence = plan.crossFile()
                ? extractions.findByProjectId(association.projectId())
                : extractions.findByCandidateId(association.projectId(), candidate.id());
        List<ExtractionResult> proven = new ArrayList<>();
        for (int index = 0; index < plan.targets().size(); index++) {
            Target target = plan.targets().get(index);
            boolean contractSource = !plan.crossFile() || index == 0;
            List<ExtractionResult> matches = evidence.stream()
                    .filter(value -> value.projectId().equals(association.projectId()))
                    .filter(value -> contractSource
                            ? candidate.id().equals(value.candidateId())
                                    && candidate.sourceContractFileId().equals(value.sourceFileId())
                            : value.candidateId() == null
                                    && !candidate.sourceContractFileId().equals(value.sourceFileId()))
                    .filter(value -> target.factType().equals(value.factType()))
                    .filter(value -> Objects.equals(target.groupKey(), value.groupKey()))
                    .filter(target::matches)
                    .filter(value -> value.sourceLocator() != null && !value.sourceLocator().isBlank())
                    .filter(value -> value.sourceText() != null && !value.sourceText().isBlank())
                    .filter(value -> files.findByProjectIdAndId(association.projectId(), value.sourceFileId())
                            .filter(file -> file.ingestionStatus() == ProjectFileIngestionStatus.AVAILABLE)
                            .filter(file -> contractSource
                                    ? file.category() == ProjectFileCategory.CONTRACT_BODY
                                    : file.category() == ProjectFileCategory.AWARD_NOTICE)
                            .isPresent())
                    .toList();
            if (matches.isEmpty()) return unavailable("SOURCE_NOT_FOUND");
            if (matches.size() != 1) return unavailable("SOURCE_AMBIGUOUS");
            proven.add(matches.get(0));
        }
        if (plan.crossFile() && proven.stream().map(ExtractionResult::sourceFileId).distinct().count() != proven.size()) {
            return unavailable("COMPARISON_SOURCES_NOT_DISTINCT");
        }
        List<Integer> indexes = plan.crossFile() || F02RuleCodes.LINE_SUM_MATCH.equals(finding.ruleCode())
                ? java.util.stream.IntStream.range(0, proven.size()).boxed().toList()
                : List.of(plan.primaryIndex());
        List<FindingSourceProjection.Source> sources = indexes.stream().map(index -> {
            ExtractionResult source = proven.get(index);
            var file = files.findByProjectIdAndId(association.projectId(), source.sourceFileId())
                    .orElseThrow(() -> new IllegalStateException("validated finding source file disappeared"));
            return new FindingSourceProjection.Source(plan.targets().get(index).role(), association.projectId(),
                    source.sourceFileId(), file.originalFilename() == null ? "项目材料" : file.originalFilename(),
                    source.pageNumber(), source.paragraphIndex(),
                    source.sourceLocator(), projectedExcerpt(source, evidence));
        }).toList();
        return FindingSourceProjection.available(plan.crossFile() ? "CROSS_FILE" : "INTERNAL", sources);
    }

    /** Projects only exact parse-bound facts selected by an immutable ReviewRun. */
    public FindingSourceProjection projectFrozen(FrozenReviewInput input, AuditCheckResult finding) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(finding, "finding must not be null");
        ProjectionPlan plan = frozenPlan(input.frozenContract(), finding);
        if (plan == null || plan.targets().stream().anyMatch(Objects::isNull)) {
            return unavailable("RULE_NOT_PROJECTABLE");
        }
        ContractCandidateConfirmation confirmation = null;
        if (input.run().sourceConfirmationId() != null) {
            if (confirmations == null) return unavailable("FROZEN_CONFIRMATION_UNVERIFIED");
            confirmation = confirmations.findByProjectAndCandidateAndId(input.run().projectId(),
                    input.run().sourceCandidateId(), input.run().sourceConfirmationId()).orElse(null);
            if (confirmation == null || !confirmation.projectId().equals(input.run().projectId())
                    || !confirmation.candidateId().equals(input.run().sourceCandidateId())
                    || !confirmation.confirmationId().equals(input.run().sourceConfirmationId())
                    || confirmation.createdAt().isAfter(input.run().requestedAt())
                    || confirmation.confirmedAt().isAfter(input.run().requestedAt())) {
                return unavailable("FROZEN_CONFIRMATION_UNVERIFIED");
            }
            ContractCandidateConfirmation frozen = confirmation;
            if (input.materials().stream().filter(material -> material.fileId().equals(frozen.sourceFileId())
                    && material.parseResultId().equals(frozen.sourceParseResultId())
                    && Objects.equals(material.roleDecisionId(), frozen.sourceRoleDecisionId())
                    && Objects.equals(material.identityDecisionId(), frozen.sourceIdentityDecisionId())
                    && material.effectiveRole() == com.fuquan.audit.contract.domain.project.DocumentRole.CONTRACT_BODY)
                    .count() != 1) return unavailable("FROZEN_CONFIRMATION_UNVERIFIED");
        }
        List<ExtractionResult> frozenFacts = new ArrayList<>();
        for (FrozenReviewMaterial material : input.materials()) {
            boolean contractSource = material.effectiveRole()
                    == com.fuquan.audit.contract.domain.project.DocumentRole.CONTRACT_BODY;
            boolean needed = plan.targets().stream().anyMatch(target ->
                    ("CONTRACT".equals(target.role()) && contractSource)
                    || ("AWARD_NOTICE".equals(target.role()) && material.effectiveRole()
                        == com.fuquan.audit.contract.domain.project.DocumentRole.AWARD_NOTICE));
            if (!needed || contractSource && confirmation != null
                    && !material.fileId().equals(confirmation.sourceFileId())) continue;
            List<ExtractionResult> exact = extractions.findBySourceFileIdAndParseResultId(
                    input.run().projectId(), material.fileId(), material.parseResultId());
            if (exact == null || exact.stream().anyMatch(value -> value == null
                    || !input.run().projectId().equals(value.projectId())
                    || !material.fileId().equals(value.sourceFileId())
                    || !material.parseResultId().equals(value.parseResultId()))) {
                return unavailable("FROZEN_PARSE_FACTS_UNVERIFIED");
            }
            java.time.Instant cutoff = contractSource && confirmation != null
                    ? confirmation.confirmedAt() : input.run().requestedAt();
            frozenFacts.addAll(exact.stream().filter(value -> !value.createdAt().isAfter(cutoff))
                    .filter(value -> !contractSource || input.run().sourceCandidateId() == null
                            || input.run().sourceCandidateId().equals(value.candidateId())).toList());
        }
        List<FindingSourceProjection.Source> sources = new ArrayList<>();
        boolean confirmedValueDiffers = false;
        for (Target target : plan.targets()) {
            com.fuquan.audit.contract.domain.project.DocumentRole role = "AWARD_NOTICE".equals(target.role())
                    ? com.fuquan.audit.contract.domain.project.DocumentRole.AWARD_NOTICE
                    : com.fuquan.audit.contract.domain.project.DocumentRole.CONTRACT_BODY;
            List<ExtractionResult> roleFacts = frozenFacts.stream().filter(value -> input.materials().stream()
                    .anyMatch(material -> material.fileId().equals(value.sourceFileId())
                            && material.effectiveRole() == role)).toList();
            // Supplier-name witnesses must agree BEFORE value filtering or proof collapse.
            // All other scalar rules, credit-code precedence and row-key resolution stay strict.
            if (F02RuleCodes.AWARD_SUPPLIER_MATCH.equals(finding.ruleCode())
                    && target.textMode() == TextMode.NAME && target.groupKey() == null) {
                List<ExtractionResult> witnesses = roleFacts.stream()
                        .filter(value -> target.factType().equals(value.factType()))
                        .filter(value -> value.candidateId() == null
                                || value.candidateId().equals(input.run().sourceCandidateId()))
                        .filter(value -> value.groupKey() == null).toList();
                if (witnesses.isEmpty()) return unavailable("SOURCE_NOT_FOUND");
                ExtractionResult first = witnesses.get(0);
                if (witnesses.stream().anyMatch(value ->
                        !first.sourceFileId().equals(value.sourceFileId())
                        || !first.parseResultId().equals(value.parseResultId())
                        || !Objects.equals(first.candidateId(), value.candidateId()))
                        || witnesses.stream().map(value -> normalized(value.normalizedValue() != null
                                ? value.normalizedValue() : value.valueText(), TextMode.NAME))
                                .distinct().count() != 1) return unavailable("SOURCE_AMBIGUOUS");
                if (!witnesses.stream().allMatch(target::matches)
                        || !witnesses.stream().allMatch(FindingSourceProjectionService::hasAnchor))
                    return unavailable("SOURCE_NOT_FOUND");
                witnesses = collapseEquivalentFrozenProofs(witnesses).stream()
                        .sorted(Comparator.comparing(ExtractionResult::paragraphIndex,
                                        Comparator.nullsFirst(Integer::compareTo))
                                .thenComparing(ExtractionResult::pageNumber, Comparator.nullsFirst(Integer::compareTo))
                                .thenComparing(ExtractionResult::sourceLocator)
                                .thenComparing(ExtractionResult::sourceText)
                                .thenComparing(value -> value.id().toString())).toList();
                for (ExtractionResult witness : witnesses) {
                    String filename = input.materials().stream()
                            .filter(value -> value.fileId().equals(witness.sourceFileId()))
                            .map(value -> value.originalFilename() == null ? "Frozen review material" : value.originalFilename())
                            .findFirst().orElse("Frozen review material");
                    sources.add(new FindingSourceProjection.Source(target.role(), input.run().projectId(),
                            witness.sourceFileId(), filename, witness.pageNumber(), witness.paragraphIndex(),
                            witness.sourceLocator(), witness.sourceText()));
                }
                continue;
            }
            ExtractionResult rowKey = null;
            if (target.groupKey() != null) {
                List<ExtractionResult> keys = roleFacts.stream()
                        .filter(value -> "LINE_ITEM_KEY".equals(value.factType()))
                        .filter(value -> target.groupKey().equals(value.normalizedValue() != null
                                ? value.normalizedValue() : value.valueText()))
                        .filter(value -> value.groupKey() != null && !value.groupKey().isBlank())
                        .filter(FindingSourceProjectionService::hasAnchor).toList();
                if (keys.isEmpty()) return unavailable("SOURCE_ROW_KEY_NOT_FOUND");
                ExtractionResult firstKey = keys.get(0);
                if (keys.stream().anyMatch(value -> !sameFrozenExtractionRow(firstKey, value))) {
                    return unavailable("SOURCE_AMBIGUOUS");
                }
                keys = collapseEquivalentFrozenProofs(keys);
                if (keys.size() != 1) return unavailable("SOURCE_AMBIGUOUS");
                rowKey = keys.get(0);
            }
            ExtractionResult resolvedRow = rowKey;
            List<ExtractionResult> matches = collapseEquivalentFrozenProofs(roleFacts.stream()
                    .filter(value -> target.factType().equals(value.factType()))
                    .filter(value -> resolvedRow == null ? Objects.equals(target.groupKey(), value.groupKey())
                            : sameFrozenExtractionRow(resolvedRow, value))
                    // For a resolved row, select the typed source before testing the confirmed value.
                    // Filtering by value first could silently choose the convenient conflicting fact.
                    .filter(value -> resolvedRow != null || target.matches(value))
                    .filter(value -> resolvedRow == null || value.valueAmount() != null)
                    .filter(FindingSourceProjectionService::hasAnchor).toList());
            if (matches.isEmpty()) return unavailable("SOURCE_NOT_FOUND");
            if (matches.size() != 1) return unavailable("SOURCE_AMBIGUOUS");
            ExtractionResult source = matches.get(0);
            confirmedValueDiffers |= !target.matches(source);
            String filename = input.materials().stream().filter(value -> value.fileId().equals(source.sourceFileId()))
                    .map(value -> value.originalFilename() == null ? "冻结审核材料" : value.originalFilename())
                    .findFirst().orElse("冻结审核材料");
            sources.add(new FindingSourceProjection.Source(target.role(), input.run().projectId(),
                    source.sourceFileId(), filename, source.pageNumber(), source.paragraphIndex(),
                    source.sourceLocator(), source.sourceText()));
        }
        if (confirmedValueDiffers) return new FindingSourceProjection(false,
                "CONFIRMED_VALUE_DIFFERS_FROM_SOURCE", plan.crossFile() ? "CROSS_FILE" : "INTERNAL", sources);
        return FindingSourceProjection.available(plan.crossFile() ? "CROSS_FILE" : "INTERNAL", sources);
    }

    private static boolean sameFrozenExtractionRow(ExtractionResult key, ExtractionResult value) {
        return key.projectId().equals(value.projectId()) && key.sourceFileId().equals(value.sourceFileId())
                && Objects.equals(key.parseResultId(), value.parseResultId())
                && Objects.equals(key.candidateId(), value.candidateId())
                && key.extractionVersion().equals(value.extractionVersion())
                && key.groupKey().equals(value.groupKey());
    }

    private static List<ExtractionResult> collapseEquivalentFrozenProofs(List<ExtractionResult> matches) {
        if (matches.size() < 2) return matches;
        ExtractionResult first = matches.get(0);
        boolean sameProof = matches.stream().allMatch(value ->
                first.projectId().equals(value.projectId())
                        && first.sourceFileId().equals(value.sourceFileId())
                        && Objects.equals(first.parseResultId(), value.parseResultId())
                        && first.factType().equals(value.factType())
                        && Objects.equals(first.groupKey(), value.groupKey())
                        && (first.valueAmount() == null ? value.valueAmount() == null
                                : value.valueAmount() != null && first.valueAmount().compareTo(value.valueAmount()) == 0)
                        && first.sourceLocator().equals(value.sourceLocator())
                        && first.sourceText().equals(value.sourceText())
                        && Objects.equals(first.pageNumber(), value.pageNumber())
                        && Objects.equals(first.paragraphIndex(), value.paragraphIndex()));
        if (!sameProof) return matches;
        ExtractionResult selected = matches.stream()
                .min(Comparator.comparing(ExtractionResult::createdAt)
                        .thenComparing(value -> value.id().toString()))
                .orElseThrow();
        return List.of(selected);
    }

    private FindingSourceProjection projectExplicit(ContractCandidate candidate, ProjectionPlan plan,
            ResolvedCandidateSupportAuthoritySet current) {
        Target award = plan.targets().get(1);
        Object candidateAward = switch (award.factType()) {
            case "AWARD_AMOUNT" -> candidate.awardAmount();
            case "AWARD_SUPPLIER" -> candidate.awardSupplier();
            case "AWARD_SUPPLIER_CREDIT_CODE" -> candidate.awardSupplierCreditCode();
            default -> null;
        };
        if (candidateAward == null) return unavailable("SUPPORT_AWARD_VALUE_UNRESOLVED");
        if (!plan.matchesCandidate()) return unavailable("PROMOTION_VALUE_MISMATCH");

        List<ExtractionResult> supportEvidence = new ArrayList<>();
        for (var source : current.supports().stream()
                .sorted(Comparator.comparing(value -> value.sourceFileId().toString())).toList()) {
            if (candidate.sourceContractFileId().equals(source.sourceFileId())) {
                return unavailable("COMPARISON_SOURCES_NOT_DISTINCT");
            }
            List<ExtractionResult> exact = extractions.findBySourceFileIdAndParseResultId(
                    candidate.projectId(), source.sourceFileId(), source.parseResultId());
            if (exact == null || exact.stream().anyMatch(fact -> fact == null
                    || !candidate.projectId().equals(fact.projectId())
                    || !source.sourceFileId().equals(fact.sourceFileId())
                    || !source.parseResultId().equals(fact.parseResultId()) || fact.candidateId() != null)) {
                return unavailable("SUPPORT_AUTHORITY_NOT_CURRENT");
            }
            List<ExtractionResult> eligible = exact.stream()
                    .filter(fact -> SUPPORT_AWARD_FACTS.contains(fact.factType())).toList();
            // Reopening immutable evidence must prove the entire validated cohort, including conflict participants.
            if (eligible.size() != source.eligibleFactIds().size()
                    || !new HashSet<>(eligible.stream().map(ExtractionResult::id).toList())
                            .equals(new HashSet<>(source.eligibleFactIds()))) {
                return unavailable("SUPPORT_AUTHORITY_NOT_CURRENT");
            }
            supportEvidence.addAll(eligible.stream()
                    .sorted(Comparator.comparing(ExtractionResult::sourceLocator)
                            .thenComparing(ExtractionResult::extractionKey)
                            .thenComparing(value -> value.id().toString())).toList());
        }
        List<ExtractionResult> primaryEvidence = extractions.findByCandidateId(candidate.projectId(), candidate.id());
        Target primary = plan.targets().get(0);
        List<ExtractionResult> primaryMatches = primaryEvidence.stream()
                .filter(value -> candidate.projectId().equals(value.projectId())
                        && candidate.id().equals(value.candidateId())
                        && candidate.sourceContractFileId().equals(value.sourceFileId()))
                .filter(value -> primary.factType().equals(value.factType())
                        && Objects.equals(primary.groupKey(), value.groupKey()))
                .filter(primary::matches).filter(FindingSourceProjectionService::hasAnchor)
                .filter(value -> files.findByProjectIdAndId(candidate.projectId(), value.sourceFileId())
                        .filter(file -> file.ingestionStatus() == ProjectFileIngestionStatus.AVAILABLE
                                && file.category() == ProjectFileCategory.CONTRACT_BODY).isPresent()).toList();
        if (primaryMatches.isEmpty()) return unavailable("SOURCE_NOT_FOUND");
        if (primaryMatches.size() != 1) return unavailable("SOURCE_AMBIGUOUS");
        List<ExtractionResult> awardMatches = supportEvidence.stream()
                .filter(value -> award.factType().equals(value.factType())
                        && Objects.equals(award.groupKey(), value.groupKey()))
                .filter(award::matches).filter(FindingSourceProjectionService::hasAnchor).toList();
        if (awardMatches.isEmpty()) return unavailable("SOURCE_NOT_FOUND");

        List<FindingSourceProjection.Source> sources = new ArrayList<>();
        List<ExtractionResult> proven = new ArrayList<>(primaryMatches);
        proven.addAll(awardMatches);
        for (int index = 0; index < proven.size(); index++) {
            ExtractionResult source = proven.get(index);
            var file = files.findByProjectIdAndId(candidate.projectId(), source.sourceFileId())
                    .filter(value -> value.projectId().equals(candidate.projectId())
                            && value.id().equals(source.sourceFileId())
                            && value.ingestionStatus() == ProjectFileIngestionStatus.AVAILABLE).orElse(null);
            if (file == null) return unavailable("SOURCE_FILE_NOT_AVAILABLE");
            sources.add(new FindingSourceProjection.Source(index == 0 ? primary.role() : award.role(),
                    candidate.projectId(), source.sourceFileId(),
                    file.originalFilename() == null ? "项目材料" : file.originalFilename(),
                    source.pageNumber(), source.paragraphIndex(), source.sourceLocator(), source.sourceText()));
        }
        return FindingSourceProjection.available("CROSS_FILE", sources);
    }

    private static boolean hasAnchor(ExtractionResult value) {
        return value.sourceLocator() != null && !value.sourceLocator().isBlank()
                && value.sourceText() != null && !value.sourceText().isBlank();
    }

    private static String projectedExcerpt(ExtractionResult primary,
                                           List<ExtractionResult> availableEvidence) {
        String current = primary.sourceText();
        if (!LINE_ITEM_FACT_ORDER.contains(primary.factType())
                || primary.groupKey() == null || primary.groupKey().isBlank()) {
            return current;
        }
        String primaryValue = normalizedVisibleValue(primary.valueText());
        String groupKey = normalizedVisibleValue(primary.groupKey());
        String normalizedCurrent = normalizedVisibleValue(current);
        if (!primaryValue.isEmpty()
                && !groupKey.isEmpty()
                && normalizedCurrent.length() > primaryValue.length()
                && normalizedCurrent.contains(primaryValue)
                && normalizedCurrent.contains(groupKey)) {
            return current;
        }

        String rowIdentity = docxTableRowIdentity(primary.sourceLocator());
        if (rowIdentity == null) return current;
        List<ExtractionResult> sameRow = availableEvidence.stream()
                .filter(value -> primary.projectId().equals(value.projectId()))
                .filter(value -> primary.sourceFileId().equals(value.sourceFileId()))
                .filter(value -> Objects.equals(primary.candidateId(), value.candidateId()))
                .filter(value -> primary.groupKey().equals(value.groupKey()))
                .filter(value -> LINE_ITEM_FACT_ORDER.contains(value.factType()))
                .filter(value -> rowIdentity.equals(docxTableRowIdentity(value.sourceLocator())))
                .toList();
        List<ExtractionResult> ordered = new ArrayList<>();
        for (String factType : LINE_ITEM_FACT_ORDER) {
            List<ExtractionResult> matches = sameRow.stream()
                    .filter(value -> factType.equals(value.factType()))
                    .toList();
            if (matches.size() != 1
                    || matches.get(0).valueText() == null
                    || matches.get(0).valueText().isBlank()) {
                return current;
            }
            ordered.add(matches.get(0));
        }

        List<String> values = ordered.stream()
                .map(ExtractionResult::valueText)
                .map(FindingSourceProjectionService::collapsedWhitespace)
                .toList();
        String completeRow = String.join(" ", values);
        if (completeRow.length() <= MAX_PROJECTED_EXCERPT_CHARS) return completeRow;

        int targetIndex = LINE_ITEM_FACT_ORDER.indexOf(primary.factType());
        String throughTarget = String.join(" ", values.subList(0, targetIndex + 1));
        if (throughTarget.length() > MAX_PROJECTED_EXCERPT_CHARS) return current;
        StringBuilder bounded = new StringBuilder(throughTarget);
        for (int index = targetIndex + 1; index < values.size(); index++) {
            String candidate = bounded + " " + values.get(index);
            if (candidate.length() > MAX_PROJECTED_EXCERPT_CHARS) break;
            bounded.append(' ').append(values.get(index));
        }
        return bounded.toString();
    }

    private static String docxTableRowIdentity(String locator) {
        if (locator == null) return null;
        Matcher matcher = DOCX_TABLE_ROW_LOCATOR.matcher(locator);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static String collapsedWhitespace(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    private static String normalizedVisibleValue(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private static ProjectionPlan plan(Contract contract, ContractCandidate candidate,
                                       AuditCheckResult finding) {
        return switch (finding.ruleCode()) {
            case F02RuleCodes.AMOUNT_WORDS_MATCH -> amountWordsPlan(contract, candidate);
            case F02RuleCodes.LINE_SUM_MATCH -> lineSumPlan(contract, candidate);
            case F02RuleCodes.AWARD_AMOUNT_MATCH -> awardAmountPlan(contract, candidate);
            case F02RuleCodes.AWARD_SUPPLIER_MATCH -> awardSupplierPlan(contract, candidate);
            case F02RuleCodes.LINE_ITEM_ARITHMETIC -> lineArithmeticPlan(contract, candidate, finding.itemKey());
            default -> null;
        };
    }

    private static ProjectionPlan frozenPlan(Contract contract, AuditCheckResult finding) {
        return switch (finding.ruleCode()) {
            case F02RuleCodes.AMOUNT_WORDS_MATCH -> {
                Target amount = Target.amount("CONTRACT", "AMOUNT_NUMERIC", null,
                        contract.contractAmount().amount());
                Target words = Target.text("CONTRACT", "AMOUNT_UPPERCASE", null,
                        contract.amountInWords(), TextMode.EXACT);
                yield amount == null || words == null ? null
                        : new ProjectionPlan(List.of(amount, words), 1, true, false);
            }
            case F02RuleCodes.LINE_ITEM_ARITHMETIC -> {
                List<ContractLineItem> lines = contract.lineItems().stream()
                        .filter(value -> Objects.equals(value.itemKey(), finding.itemKey())).toList();
                if (lines.size() != 1) yield null;
                ContractLineItem line = lines.get(0);
                Target declared = Target.amount("CONTRACT", "LINE_ITEM_DECLARED_AMOUNT", finding.itemKey(),
                        line.declaredAmount() == null ? null : line.declaredAmount().amount());
                if (line.basis() == com.fuquan.audit.contract.domain.ContractLineItemBasis.AMOUNT_ONLY) {
                    yield declared == null ? null
                            : new ProjectionPlan(List.of(declared), 0, true, false);
                }
                Target quantity = Target.amount("CONTRACT", "LINE_ITEM_QUANTITY",
                        finding.itemKey(), line.quantity());
                Target unitPrice = Target.amount("CONTRACT", "LINE_ITEM_UNIT_PRICE",
                        finding.itemKey(), line.unitPrice());
                yield quantity == null || unitPrice == null || declared == null ? null
                        : new ProjectionPlan(List.of(quantity, unitPrice, declared), 2, true, false);
            }
            case F02RuleCodes.LINE_SUM_MATCH -> {
                if (contract.lineItems().isEmpty()) yield null;
                List<Target> targets = new ArrayList<>();
                targets.add(Target.amount("CONTRACT", "AMOUNT_NUMERIC", null,
                        contract.contractAmount().amount()));
                for (ContractLineItem line : contract.lineItems()) {
                    targets.add(Target.amount("CONTRACT", "LINE_ITEM_DECLARED_AMOUNT", line.itemKey(),
                            line.declaredAmount() == null ? null : line.declaredAmount().amount()));
                }
                yield targets.stream().anyMatch(Objects::isNull) ? null
                        : new ProjectionPlan(List.copyOf(targets), 0, true, false);
            }
            case F02RuleCodes.AWARD_AMOUNT_MATCH -> contract.awardNotice() == null ? null
                    : new ProjectionPlan(List.of(
                            Target.amount("CONTRACT", "AMOUNT_NUMERIC", null,
                                    contract.contractAmount().amount()),
                            Target.amount("AWARD_NOTICE", "AWARD_AMOUNT", null,
                                    contract.awardNotice().winningAmount().amount())), 1, true, true);
            case F02RuleCodes.AWARD_SUPPLIER_MATCH -> frozenAwardSupplierPlan(contract);
            default -> null;
        };
    }

    private static ProjectionPlan frozenAwardSupplierPlan(Contract contract) {
        if (contract.awardNotice() == null) return null;
        String contractCode = normalized(contract.partyBCreditCode(), TextMode.CODE);
        String awardCode = normalized(contract.awardNotice().winningBidderCreditCode(), TextMode.CODE);
        if (contractCode != null && awardCode != null) {
            return new ProjectionPlan(List.of(
                    Target.text("CONTRACT", "CREDIT_CODE", null, contract.partyBCreditCode(), TextMode.CODE),
                    Target.text("AWARD_NOTICE", "AWARD_SUPPLIER_CREDIT_CODE", null,
                            contract.awardNotice().winningBidderCreditCode(), TextMode.CODE)), 1, true, true);
        }
        Target party = Target.text("CONTRACT", "PARTY_B", null, contract.partyB(), TextMode.NAME);
        Target award = Target.text("AWARD_NOTICE", "AWARD_SUPPLIER", null,
                contract.awardNotice().winningBidder(), TextMode.NAME);
        return party == null || award == null ? null
                : new ProjectionPlan(List.of(party, award), 1, true, true);
    }

    private static ProjectionPlan amountWordsPlan(Contract contract, ContractCandidate candidate) {
        Target numeric = Target.amount("CONTRACT", "AMOUNT_NUMERIC", null, contract.contractAmount().amount());
        Target uppercase = Target.text("CONTRACT", "AMOUNT_UPPERCASE", null,
                contract.amountInWords(), TextMode.EXACT);
        if (uppercase == null) return null;
        return new ProjectionPlan(List.of(numeric, uppercase), 1,
                numeric.same(candidate.contractAmount()) && uppercase.same(candidate.amountInWords()), false);
    }

    private static ProjectionPlan awardAmountPlan(Contract contract, ContractCandidate candidate) {
        if (contract.awardNotice() == null) return null;
        Target contractAmount = Target.amount("CONTRACT", "AMOUNT_NUMERIC", null,
                contract.contractAmount().amount());
        Target awardAmount = Target.amount("AWARD_NOTICE", "AWARD_AMOUNT", null,
                contract.awardNotice().winningAmount().amount());
        return new ProjectionPlan(List.of(contractAmount, awardAmount), 1,
                contractAmount.same(candidate.contractAmount()) && awardAmount.same(candidate.awardAmount()), true);
    }

    private static ProjectionPlan lineArithmeticPlan(Contract contract, ContractCandidate candidate,
                                                      String itemKey) {
        List<ContractLineItem> formal = contract.lineItems().stream()
                .filter(line -> Objects.equals(line.itemKey(), itemKey)).toList();
        List<ContractCandidateLineItem> extracted = candidate.lineItems().stream()
                .filter(line -> Objects.equals(line.itemKey(), itemKey)).toList();
        if (formal.size() != 1 || extracted.size() != 1) return null;
        ContractLineItem line = formal.get(0);
        ContractCandidateLineItem candidateLine = extracted.get(0);
        Target declared = Target.amount("CONTRACT", "LINE_ITEM_DECLARED_AMOUNT", itemKey,
                line.declaredAmount().amount());
        if (line.basis() == com.fuquan.audit.contract.domain.ContractLineItemBasis.AMOUNT_ONLY) {
            return declared == null ? null : new ProjectionPlan(List.of(declared), 0,
                    declared.same(candidateLine.declaredAmount()), false);
        }
        List<Target> targets = List.of(
                Target.amount("CONTRACT", "LINE_ITEM_QUANTITY", itemKey, line.quantity()),
                Target.amount("CONTRACT", "LINE_ITEM_UNIT_PRICE", itemKey, line.unitPrice()),
                declared);
        return new ProjectionPlan(targets, 2,
                targets.get(0).same(candidateLine.quantity())
                        && targets.get(1).same(candidateLine.unitPrice())
                        && targets.get(2).same(candidateLine.declaredAmount()), false);
    }

    private static ProjectionPlan lineSumPlan(Contract contract, ContractCandidate candidate) {
        if (contract.lineItems().isEmpty() || contract.lineItems().size() != candidate.lineItems().size()) return null;
        List<Target> targets = new ArrayList<>();
        Target contractAmount = Target.amount("CONTRACT", "AMOUNT_NUMERIC", null,
                contract.contractAmount().amount());
        targets.add(contractAmount);
        boolean matches = contractAmount.same(candidate.contractAmount());
        for (ContractLineItem line : contract.lineItems()) {
            List<ContractCandidateLineItem> candidateLines = candidate.lineItems().stream()
                    .filter(value -> Objects.equals(value.itemKey(), line.itemKey())).toList();
            if (candidateLines.size() != 1) return null;
            Target declared = Target.amount("CONTRACT", "LINE_ITEM_DECLARED_AMOUNT", line.itemKey(),
                    line.declaredAmount().amount());
            targets.add(declared);
            matches &= declared.same(candidateLines.get(0).declaredAmount());
        }
        return new ProjectionPlan(List.copyOf(targets), 0, matches, false);
    }

    private static ProjectionPlan awardSupplierPlan(Contract contract, ContractCandidate candidate) {
        if (contract.awardNotice() == null) return null;
        String contractCode = normalized(contract.partyBCreditCode(), TextMode.CODE);
        String awardCode = normalized(contract.awardNotice().winningBidderCreditCode(), TextMode.CODE);
        if (contractCode != null && awardCode != null) {
            Target party = Target.text("CONTRACT", "CREDIT_CODE", null,
                    contract.partyBCreditCode(), TextMode.CODE);
            Target award = Target.text("AWARD_NOTICE", "AWARD_SUPPLIER_CREDIT_CODE", null,
                    contract.awardNotice().winningBidderCreditCode(), TextMode.CODE);
            return new ProjectionPlan(List.of(party, award), 1,
                    party.same(candidate.partyBCreditCode()) && award.same(candidate.awardSupplierCreditCode()), true);
        }
        Target party = Target.text("CONTRACT", "PARTY_B", null, contract.partyB(), TextMode.NAME);
        Target award = Target.text("AWARD_NOTICE", "AWARD_SUPPLIER", null,
                contract.awardNotice().winningBidder(), TextMode.NAME);
        return new ProjectionPlan(List.of(party, award), 1,
                party.same(candidate.partyB()) && award.same(candidate.awardSupplier()), true);
    }

    private static FindingSourceProjection unavailable(String reason) {
        return FindingSourceProjection.unavailable(reason);
    }

    private record ProjectionPlan(List<Target> targets, int primaryIndex, boolean matchesCandidate,
                                  boolean crossFile) { }

    private enum TextMode { EXACT, NAME, CODE }

    private record Target(String role, String factType, String groupKey, BigDecimal amount,
                          String text, TextMode textMode) {
        static Target amount(String role, String factType, String groupKey, BigDecimal value) {
            return value == null ? null : new Target(role, factType, groupKey, value, null, null);
        }
        static Target text(String role, String factType, String groupKey, String value, TextMode mode) {
            String normalized = normalized(value, mode);
            return normalized == null ? null : new Target(role, factType, groupKey, null, normalized, mode);
        }
        boolean same(Object candidateValue) {
            if (amount != null) return candidateValue instanceof BigDecimal value && amount.compareTo(value) == 0;
            return candidateValue instanceof String value && text.equals(normalized(value, textMode));
        }
        boolean matches(ExtractionResult evidence) {
            if (amount != null) return evidence.valueAmount() != null && amount.compareTo(evidence.valueAmount()) == 0;
            String value = evidence.normalizedValue() != null ? evidence.normalizedValue() : evidence.valueText();
            return text.equals(normalized(value, textMode));
        }
    }

    private static String normalized(String value, TextMode mode) {
        if (value == null || value.isBlank()) return null;
        return switch (mode) {
            case EXACT -> value.trim();
            case NAME -> Normalizer.normalize(value, Normalizer.Form.NFKC)
                    .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            case CODE -> value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        };
    }
}
