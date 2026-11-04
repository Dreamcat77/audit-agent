package com.fuquan.audit.integration.dm8;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.review.application.port.FindingAcknowledgementReadRepository;
import com.fuquan.audit.review.application.port.FindingAcknowledgementRepository;
import com.fuquan.audit.review.domain.FindingAcknowledgement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8FindingAcknowledgementRepository
        implements FindingAcknowledgementRepository, FindingAcknowledgementReadRepository {

    private static final Comparator<FindingAcknowledgement> ACKNOWLEDGEMENT_ORDER =
            Comparator.comparing(FindingAcknowledgement::acknowledgedAt)
                    .thenComparing(acknowledgement -> acknowledgement.id().toString());

    private final FindingAcknowledgementMapper mapper;

    public Dm8FindingAcknowledgementRepository(FindingAcknowledgementMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(FindingAcknowledgement acknowledgement) {
        FindingAcknowledgement fact = Objects.requireNonNull(
                acknowledgement, "acknowledgement must not be null");
        try {
            if (mapper.insert(toRow(fact)) != 1) {
                throw new IllegalStateException("finding acknowledgement insert did not affect one row");
            }
        } catch (DuplicateKeyException duplicate) {
            try {
                if (mapper.findByCheckResultId(fact.checkResultId().toString()) != null) {
                    throw new BusinessException(
                            BusinessErrorCode.CONFLICT, "finding acknowledgement already exists");
                }
            } catch (DataAccessException verificationFailure) {
                throw duplicate;
            }
            throw duplicate;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FindingAcknowledgement> findByCheckResultId(UUID checkResultId) {
        UUID resultId = Objects.requireNonNull(checkResultId, "checkResultId must not be null");
        return Optional.ofNullable(mapper.findByCheckResultId(resultId.toString())).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FindingAcknowledgement> findByTaskId(UUID taskId) {
        UUID durableTaskId = Objects.requireNonNull(taskId, "taskId must not be null");
        return mapper.findByTaskId(durableTaskId.toString()).stream()
                .map(this::toDomain)
                .sorted(ACKNOWLEDGEMENT_ORDER)
                .toList();
    }

    private FindingAcknowledgementPersistenceRow toRow(FindingAcknowledgement acknowledgement) {
        return new FindingAcknowledgementPersistenceRow(
                acknowledgement.id().toString(),
                acknowledgement.checkResultId().toString(),
                acknowledgement.acknowledgedBy(),
                LocalDateTime.ofInstant(acknowledgement.acknowledgedAt(), ZoneOffset.UTC),
                acknowledgement.requestId(),
                acknowledgement.traceId(),
                acknowledgement.ruleVersion());
    }

    private FindingAcknowledgement toDomain(FindingAcknowledgementPersistenceRow row) {
        return new FindingAcknowledgement(
                UUID.fromString(row.id()),
                UUID.fromString(row.checkResultId()),
                row.acknowledgedBy(),
                row.acknowledgedAt().toInstant(ZoneOffset.UTC),
                row.requestId(),
                row.traceId(),
                row.ruleVersion());
    }
}
