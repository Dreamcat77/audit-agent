package com.fuquan.audit.review.infra.memory;

import com.fuquan.audit.review.application.port.AuditCheckResultRepository;
import com.fuquan.audit.review.application.port.FindingAcknowledgementReadRepository;
import com.fuquan.audit.review.application.port.FindingAcknowledgementRepository;
import com.fuquan.audit.review.domain.FindingAcknowledgement;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Local immutable acknowledgement store keyed by the uniquely acknowledged result. */
@Repository
@Profile("local")
public class InMemoryFindingAcknowledgementRepository
        implements FindingAcknowledgementRepository, FindingAcknowledgementReadRepository {

    private static final Comparator<FindingAcknowledgement> ACKNOWLEDGEMENT_ORDER =
            Comparator.comparing(FindingAcknowledgement::acknowledgedAt)
                    .thenComparing(acknowledgement -> acknowledgement.id().toString());

    private final AuditCheckResultRepository checkResultRepository;
    private final ConcurrentMap<UUID, FindingAcknowledgement> acknowledgementsByCheckResultId =
            new ConcurrentHashMap<>();

    public InMemoryFindingAcknowledgementRepository(AuditCheckResultRepository checkResultRepository) {
        this.checkResultRepository = Objects.requireNonNull(checkResultRepository, "checkResultRepository must not be null");
    }

    @Override
    public void create(FindingAcknowledgement acknowledgement) {
        FindingAcknowledgement fact = Objects.requireNonNull(acknowledgement, "acknowledgement must not be null");
        if (acknowledgementsByCheckResultId.putIfAbsent(fact.checkResultId(), fact) != null) {
            throw new IllegalStateException("finding acknowledgement already exists");
        }
    }

    @Override
    public Optional<FindingAcknowledgement> findByCheckResultId(UUID checkResultId) {
        return Optional.ofNullable(acknowledgementsByCheckResultId.get(checkResultId));
    }

    @Override
    public List<FindingAcknowledgement> findByTaskId(UUID taskId) {
        return acknowledgementsByCheckResultId.values().stream()
                .filter(acknowledgement -> checkResultRepository.findById(acknowledgement.checkResultId())
                        .map(result -> result.taskId().equals(taskId))
                        .orElse(false))
                .sorted(ACKNOWLEDGEMENT_ORDER)
                .toList();
    }
}
