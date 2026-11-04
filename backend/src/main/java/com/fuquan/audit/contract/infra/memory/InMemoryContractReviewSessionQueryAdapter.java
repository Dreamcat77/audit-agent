package com.fuquan.audit.contract.infra.memory;

import com.fuquan.audit.contract.application.ReviewSessionContractSummary;
import com.fuquan.audit.contract.application.ReviewSessionTaskQuery;
import com.fuquan.audit.contract.application.ReviewSessionTaskSummary;
import com.fuquan.audit.contract.application.port.ContractReviewSessionQueryPort;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractReviewSession;
import com.fuquan.audit.security.config.LocalAccountProperties;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** local profile 的全局 ReviewSession 任务读模型。 */
@Repository
@Profile("local")
public class InMemoryContractReviewSessionQueryAdapter implements ContractReviewSessionQueryPort {
    private final InMemoryContractReviewStore store;
    private final LocalAccountProperties localAccountProperties;
    private final Map<UUID, Contract> contracts = LocalContractFixtures.contracts().stream()
            .collect(Collectors.toUnmodifiableMap(Contract::id, Function.identity()));

    public InMemoryContractReviewSessionQueryAdapter(
            InMemoryContractReviewStore store,
            LocalAccountProperties localAccountProperties) {
        this.store = store;
        this.localAccountProperties = localAccountProperties;
    }

    @Override
    public List<ReviewSessionTaskSummary> findPage(ReviewSessionTaskQuery query) {
        return filtered(query)
                .sorted(Comparator.comparing(ContractReviewSession::createdAt).reversed()
                        .thenComparing(ContractReviewSession::id, Comparator.reverseOrder()))
                .skip(query.offset())
                .limit(query.size())
                .map(this::summary)
                .toList();
    }

    @Override
    public long count(ReviewSessionTaskQuery query) {
        return filtered(query).count();
    }

    @Override
    public Optional<String> findCreatorDisplayName(UUID sessionId) {
        return store.findSession(sessionId)
                .flatMap(session -> creatorDisplayName(session.createdBy()));
    }

    private Stream<ContractReviewSession> filtered(ReviewSessionTaskQuery query) {
        String keyword = query.keyword() == null ? null : query.keyword().toUpperCase(Locale.ROOT);
        return store.activeSessions().stream()
                .filter(session -> query.status() == null || session.status() == query.status())
                .filter(session -> {
                    Contract contract = contracts.get(session.contractId());
                    return contract != null && (keyword == null
                            || contains(session.name(), keyword)
                            || contains(contract.contractNo(), keyword)
                            || contains(contract.contractName(), keyword)
                            || contains(contract.partyB(), keyword));
                });
    }

    private ReviewSessionTaskSummary summary(ContractReviewSession session) {
        Contract contract = contracts.get(session.contractId());
        ReviewSessionContractSummary contractSummary = new ReviewSessionContractSummary(
                contract.id(), contract.contractNo(), contract.contractName(), contract.contractType(),
                contract.partyA(), contract.partyB(), contract.partyBCreditCode(),
                contract.contractAmount().amount(), contract.amountInWords(), contract.status(), contract.versionNo(),
                contract.createdAt(), contract.updatedAt());
        return new ReviewSessionTaskSummary(
                session.id(), session.name(), session.reviewType(), session.status(), session.remark(),
                session.contractVersion(), session.createdBy(),
                creatorDisplayName(session.createdBy()).orElse(null),
                session.createdAt(), session.updatedAt(),
                session.materialsConfirmedAt(), session.versionNo(), session.deletedAt(),
                session.deletedBy(), contractSummary);
    }

    private static boolean contains(String value, String keyword) {
        return value.toUpperCase(Locale.ROOT).contains(keyword);
    }

    private Optional<String> creatorDisplayName(String createdBy) {
        String accountId = localAccountProperties.getId();
        String realName = localAccountProperties.getRealName();
        if (accountId == null || realName == null
                || !accountId.equals(createdBy) || realName.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(realName.trim());
    }
}
