package com.fuquan.audit.contract.application.project.extraction;

import com.fuquan.audit.contract.domain.project.CandidateAuthorityMode;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded allow-lists and canonical ordering for Candidate-derived facts. */
public final class ContractCandidateFactPolicy {
    private static final Set<String> EXPLICIT_PRIMARY_FACT_TYPES = Set.of(
            "CONTRACT_NO", "CONTRACT_NAME", "CONTRACT_TYPE", "PARTY_A", "PARTY_B", "CREDIT_CODE",
            "AMOUNT_NUMERIC", "AMOUNT_UPPERCASE", "LINE_ITEM_KEY", "LINE_ITEM_NAME",
            "LINE_ITEM_QUANTITY", "LINE_ITEM_UNIT_PRICE", "LINE_ITEM_DECLARED_AMOUNT");
    private static final Set<String> SUPPORT_AWARD_FACT_TYPES = Set.of(
            "AWARD_AMOUNT", "AWARD_SUPPLIER", "AWARD_SUPPLIER_CREDIT_CODE");
    /**
     * Produces only aggregation-authorized facts. Provenance callers retain their exact source rows separately.
     */
    public List<ExtractionResult> authorizedFacts(ProjectFile primaryFile, List<ExtractionResult> primaryFacts,
            List<FactSource> supportSources, CandidateAuthorityMode mode) {
        Objects.requireNonNull(primaryFile, "primaryFile must not be null");
        Objects.requireNonNull(primaryFacts, "primaryFacts must not be null");
        Objects.requireNonNull(supportSources, "supportSources must not be null");
        Objects.requireNonNull(mode, "mode must not be null");

        if (mode == CandidateAuthorityMode.PRIMARY_ONLY_COMPAT) {
            return List.copyOf(primaryFacts);
        }
        List<SourcedFact> primary = primaryFacts.stream()
                .filter(Objects::nonNull)
                .filter(fact -> primaryFile.id().equals(fact.sourceFileId()))
                .map(fact -> new SourcedFact(DocumentRole.CONTRACT_BODY, primaryFile.id(), 0, fact))
                .toList();
        List<SourcedFact> support = supportSources.stream()
                .filter(Objects::nonNull)
                .filter(source -> source.role() == DocumentRole.AWARD_NOTICE)
                .flatMap(source -> source.facts().stream()
                        .filter(fact -> source.sourceFileId().equals(fact.sourceFileId()))
                        .map(fact -> new SourcedFact(source.role(), source.sourceFileId(), source.parseVersion(), fact)))
                .toList();
        return sorted(java.util.stream.Stream.concat(
                        primary.stream().filter(value -> EXPLICIT_PRIMARY_FACT_TYPES.contains(value.fact().factType())),
                        support.stream().filter(value -> SUPPORT_AWARD_FACT_TYPES.contains(value.fact().factType())))
                .toList());
    }

    private static List<ExtractionResult> sorted(List<SourcedFact> facts) {
        return facts.stream().sorted(Comparator
                        .comparing((SourcedFact value) -> value.role().name())
                        .thenComparing(SourcedFact::sourceFileId)
                        .thenComparingInt(SourcedFact::parseVersion)
                        .thenComparing(value -> value.fact().factType())
                        .thenComparing(value -> value.fact().groupKey(), Comparator.nullsFirst(String::compareTo))
                        .thenComparing(value -> value.fact().sourceLocator())
                        .thenComparing(value -> value.fact().extractionKey()))
                .map(SourcedFact::fact)
                .toList();
    }

    public record FactSource(DocumentRole role, UUID sourceFileId, int parseVersion,
            List<ExtractionResult> facts) {
        public FactSource {
            Objects.requireNonNull(role, "role must not be null");
            Objects.requireNonNull(sourceFileId, "sourceFileId must not be null");
            if (parseVersion <= 0) throw new IllegalArgumentException("parseVersion must be positive");
            facts = List.copyOf(Objects.requireNonNull(facts, "facts must not be null"));
        }
    }

    private record SourcedFact(DocumentRole role, UUID sourceFileId, int parseVersion, ExtractionResult fact) { }
}
