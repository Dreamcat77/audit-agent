package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.contract.application.ReviewSessionContractSummary;
import com.fuquan.audit.contract.application.ReviewSessionTaskQuery;
import com.fuquan.audit.contract.application.ReviewSessionTaskSummary;
import com.fuquan.audit.contract.application.port.ContractReviewSessionQueryPort;
import com.fuquan.audit.contract.domain.ReviewSessionStatus;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** DM8 one-join ReviewSession task projection with database pagination. */
@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ContractReviewSessionQueryAdapter implements ContractReviewSessionQueryPort {
    private final ContractReviewSessionMapper mapper;

    public Dm8ContractReviewSessionQueryAdapter(ContractReviewSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ReviewSessionTaskSummary> findPage(ReviewSessionTaskQuery query) {
        String status = query.status() == null ? null : query.status().name();
        return mapper.findTaskPage(status, query.keyword(), query.offset(), query.offset() + query.size())
                .stream().map(Dm8ContractReviewSessionQueryAdapter::summary).toList();
    }

    @Override
    public long count(ReviewSessionTaskQuery query) {
        return mapper.countTasks(
                query.status() == null ? null : query.status().name(), query.keyword());
    }

    @Override
    public Optional<String> findCreatorDisplayName(UUID sessionId) {
        return Optional.ofNullable(mapper.findCreatorDisplayName(sessionId.toString()));
    }

    private static ReviewSessionTaskSummary summary(ReviewSessionTaskPersistenceRow row) {
        ReviewSessionContractSummary contract = new ReviewSessionContractSummary(
                UUID.fromString(row.getContractId()), row.getContractNo(), row.getContractName(),
                row.getContractType(), row.getPartyA(), row.getPartyB(), row.getPartyBCreditCode(),
                row.getContractAmount(), row.getAmountInWords(), row.getContractStatus(), row.getContractVersionNo(),
                instant(row.getContractCreatedAt()), instant(row.getContractUpdatedAt()));
        return new ReviewSessionTaskSummary(
                UUID.fromString(row.getSessionId()), row.getName(), row.getReviewType(),
                ReviewSessionStatus.valueOf(row.getSessionStatus()), row.getRemark(),
                row.getContractVersion(), row.getCreatedBy(), row.getCreatedByName(),
                instant(row.getSessionCreatedAt()),
                instant(row.getSessionUpdatedAt()), nullableInstant(row.getMaterialsConfirmedAt()),
                row.getSessionVersionNo(), nullableInstant(row.getDeletedAt()), row.getDeletedBy(), contract);
    }

    private static java.time.Instant instant(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }

    private static java.time.Instant nullableInstant(LocalDateTime value) {
        return value == null ? null : instant(value);
    }
}
