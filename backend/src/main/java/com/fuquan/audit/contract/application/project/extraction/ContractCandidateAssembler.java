package com.fuquan.audit.contract.application.project.extraction;

import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ContractCandidateAuthoritySource;
import com.fuquan.audit.contract.domain.project.ContractCandidateLineItem;
import com.fuquan.audit.contract.domain.project.CandidateAuthorityMode;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ContractCandidateAssembler {
    private static final Pattern LOCATOR_NUMBER = Pattern.compile("(?:^|/)(page|paragraph|table|row):([0-9]+)");
    private static final Pattern CANONICAL_RANGE = Pattern.compile(
            "^parse-result:[0-9a-f-]{36}:canonical-codepoint:([0-9]{1,9})-([0-9]{1,9})$");
    private final Clock clock;
    public ContractCandidateAssembler(Clock clock) { this.clock = clock; }

    public ContractCandidate assemble(UUID projectId, ProjectFile sourceFile, List<ExtractionResult> facts) {
        UUID candidateId = UUID.randomUUID();
        ContractCandidateDerivedFacts derived = derive(candidateId, sourceFile, facts, List.of(),
                CandidateAuthorityMode.PRIMARY_ONLY_COMPAT);
        return ContractCandidate.draft(candidateId, projectId, sourceFile.id(),
                derived.contractNo(), derived.contractName(), derived.contractType(),
                derived.partyA(), derived.partyB(), derived.partyBCreditCode(),
                derived.contractAmount(), derived.amountInWords(),
                derived.awardAmount(), derived.awardSupplier(), derived.awardSupplierCreditCode(),
                derived.lineItems(), clock.instant());
    }

    public ContractCandidate assemble(UUID candidateId, UUID projectId, ProjectFile sourceFile,
            UUID parseResultId, UUID roleDecisionId, String factFingerprint,
            List<ExtractionResult> facts) {
        return assemble(candidateId, projectId, sourceFile, parseResultId, roleDecisionId,
                null, factFingerprint, facts);
    }

    public ContractCandidate assemble(UUID candidateId, UUID projectId, ProjectFile sourceFile,
            UUID parseResultId, UUID roleDecisionId, UUID identityDecisionId, String factFingerprint,
            List<ExtractionResult> facts) {
        return assemble(candidateId, projectId, sourceFile, parseResultId, roleDecisionId,
                identityDecisionId, ContractCandidateAuthoritySource.HUMAN_CONFIRMATION,
                factFingerprint, facts);
    }

    public ContractCandidate assemble(UUID candidateId, UUID projectId, ProjectFile sourceFile,
            UUID parseResultId, UUID roleDecisionId, UUID identityDecisionId,
            ContractCandidateAuthoritySource authoritySource, String factFingerprint,
            List<ExtractionResult> facts) {
        ContractCandidateDerivedFacts derived = derive(candidateId, sourceFile, facts, List.of(),
                CandidateAuthorityMode.PRIMARY_ONLY_COMPAT);
        return ContractCandidate.draftWithAuthority(candidateId, projectId, sourceFile.id(),
                parseResultId, roleDecisionId, identityDecisionId,
                authoritySource,
                factFingerprint, derived.contractNo(), derived.contractName(), derived.contractType(),
                derived.partyA(), derived.partyB(), derived.partyBCreditCode(),
                derived.contractAmount(), derived.amountInWords(),
                derived.awardAmount(), derived.awardSupplier(), derived.awardSupplierCreditCode(),
                derived.lineItems(), clock.instant());
    }

    /**
     * Projects only replaceable business facts. Candidate primary authority is deliberately not part of this value.
     */
    public ContractCandidateDerivedFacts derive(UUID candidateId, ProjectFile primaryFile,
            List<ExtractionResult> primaryFacts, List<ContractCandidateFactPolicy.FactSource> supportSources,
            CandidateAuthorityMode mode) {
        List<ExtractionResult> facts = new ContractCandidateFactPolicy().authorizedFacts(primaryFile, primaryFacts,
                supportSources, mode);
        return new ContractCandidateDerivedFacts(text(facts, "CONTRACT_NO"), text(facts, "CONTRACT_NAME"),
                text(facts, "CONTRACT_TYPE"),
                text(facts, "PARTY_A"), text(facts, "PARTY_B"), text(facts, "CREDIT_CODE"),
                amount(facts, "AMOUNT_NUMERIC"), text(facts, "AMOUNT_UPPERCASE"),
                amount(facts, "AWARD_AMOUNT"), text(facts, "AWARD_SUPPLIER"),
                text(facts, "AWARD_SUPPLIER_CREDIT_CODE"), lineItems(primaryFile.projectId(), candidateId, facts));
    }

    private static List<ContractCandidateLineItem> lineItems(UUID projectId, UUID candidateId,
                                                             List<ExtractionResult> facts) {
        Map<String, List<ExtractionResult>> groups = new LinkedHashMap<>();
        facts.stream().filter(fact -> fact.factType().startsWith("LINE_ITEM_") && fact.groupKey() != null)
                .forEach(fact -> groups.computeIfAbsent(fact.groupKey(), ignored -> new ArrayList<>()).add(fact));
        List<ContractCandidateLineItem> lines = new ArrayList<>();
        groups.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparing(ContractCandidateAssembler::sourceOrder)))
                .forEach(entry -> {
            String groupKey = entry.getKey();
            List<ExtractionResult> groupFacts = entry.getValue();
            lines.add(new ContractCandidateLineItem(
                UUID.randomUUID(), projectId, candidateId, text(groupFacts, "LINE_ITEM_KEY"),
                text(groupFacts, "LINE_ITEM_NAME"), amount(groupFacts, "LINE_ITEM_QUANTITY"),
                amount(groupFacts, "LINE_ITEM_UNIT_PRICE"), amount(groupFacts, "LINE_ITEM_DECLARED_AMOUNT"),
                lines.size()));
        });
        return List.copyOf(lines);
    }

    private static SourceOrder sourceOrder(List<ExtractionResult> facts) {
        return facts.stream().map(ExtractionResult::sourceLocator).filter(Objects::nonNull)
                .map(ContractCandidateAssembler::sourceOrder)
                .min(Comparator.naturalOrder()).orElse(SourceOrder.last());
    }

    private static SourceOrder sourceOrder(String locator) {
        int page = Integer.MAX_VALUE;
        int paragraph = Integer.MAX_VALUE;
        int table = Integer.MAX_VALUE;
        int row = Integer.MAX_VALUE;
        Matcher matcher = LOCATOR_NUMBER.matcher(locator);
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(2));
            switch (matcher.group(1)) {
                case "page" -> page = value;
                case "paragraph" -> paragraph = value;
                case "table" -> table = value;
                case "row" -> row = value;
                default -> { }
            }
        }
        int canonicalOffset = Integer.MAX_VALUE;
        Matcher canonical = CANONICAL_RANGE.matcher(locator);
        if (canonical.matches()) {
            int start = Integer.parseInt(canonical.group(1));
            int end = Integer.parseInt(canonical.group(2));
            if (end > start) canonicalOffset = start;
        }
        return new SourceOrder(page, paragraph, table, row, canonicalOffset, locator);
    }

    private record SourceOrder(int page, int paragraph, int table, int row, int canonicalOffset, String locator)
            implements Comparable<SourceOrder> {
        private static SourceOrder last() {
            return new SourceOrder(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, "");
        }

        @Override public int compareTo(SourceOrder other) {
            int compared = Integer.compare(page, other.page);
            if (compared == 0) compared = Integer.compare(paragraph, other.paragraph);
            if (compared == 0) compared = Integer.compare(table, other.table);
            if (compared == 0) compared = Integer.compare(row, other.row);
            if (compared == 0) compared = Integer.compare(canonicalOffset, other.canonicalOffset);
            return compared == 0 ? locator.compareTo(other.locator) : compared;
        }
    }

    private static String text(List<ExtractionResult> facts, String type) {
        List<String> values = facts.stream().filter(value -> value.factType().equals(type))
                .map(ExtractionResult::normalizedValue).filter(Objects::nonNull).distinct().toList();
        return values.size() == 1 ? values.get(0) : null;
    }

    private static BigDecimal amount(List<ExtractionResult> facts, String type) {
        List<BigDecimal> values = new ArrayList<>();
        for (ExtractionResult fact : facts) {
            if (!fact.factType().equals(type) || fact.valueAmount() == null) continue;
            if (values.stream().noneMatch(existing -> existing.compareTo(fact.valueAmount()) == 0)) {
                values.add(fact.valueAmount());
            }
        }
        return values.size() == 1 ? values.get(0) : null;
    }
}
