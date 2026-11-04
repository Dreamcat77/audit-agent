package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.procurement.application.port.ProcurementTenderFindingRepository;
import com.fuquan.audit.procurement.domain.ProcurementTenderFinding;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProcurementTenderFindingRepository implements ProcurementTenderFindingRepository {
    private final ProcurementTenderFindingMapper mapper;

    public Dm8ProcurementTenderFindingRepository(ProcurementTenderFindingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void replaceForReview(UUID projectId, UUID reviewId, List<ProcurementTenderFinding> findings) {
        mapper.deleteByReviewId(projectId.toString(), reviewId.toString());
        if (!findings.isEmpty()) mapper.insertBatch(findings.stream().map(Dm8ProcurementTenderFindingRepository::toRow).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcurementTenderFinding> findByReviewId(UUID projectId, UUID reviewId) {
        return mapper.findByReviewId(projectId.toString(), reviewId.toString()).stream()
                .map(Dm8ProcurementTenderFindingRepository::toDomain).toList();
    }

    private static ProcurementTenderFindingPersistenceRow toRow(ProcurementTenderFinding finding) {
        ProcurementTenderFindingPersistenceRow row = new ProcurementTenderFindingPersistenceRow();
        row.setId(finding.id().toString()); row.setProjectId(finding.projectId().toString());
        row.setReviewId(finding.reviewId().toString()); row.setRuleCode(finding.ruleCode());
        row.setRuleName(finding.ruleName()); row.setRiskCategory(finding.riskCategory());
        row.setRiskLevel(finding.riskLevel()); row.setMatchedText(finding.matchedText());
        row.setSourceLocator(finding.sourceLocator()); row.setPageNumber(finding.pageNumber());
        row.setRemediationSuggestion(finding.remediationSuggestion());
        row.setSource(finding.source()); row.setRiskReason(finding.riskReason());
        row.setCreatedTime(LocalDateTime.ofInstant(finding.createdTime(), ZoneOffset.UTC));
        return row;
    }

    private static ProcurementTenderFinding toDomain(ProcurementTenderFindingPersistenceRow row) {
        return new ProcurementTenderFinding(UUID.fromString(row.getId()), UUID.fromString(row.getProjectId()),
                UUID.fromString(row.getReviewId()), row.getRuleCode(), row.getRuleName(), row.getRiskCategory(),
                row.getRiskLevel(), row.getMatchedText(), row.getSourceLocator(), row.getPageNumber(),
                row.getRemediationSuggestion(), row.getSource(), row.getRiskReason(),
                row.getCreatedTime().toInstant(ZoneOffset.UTC));
    }
}
