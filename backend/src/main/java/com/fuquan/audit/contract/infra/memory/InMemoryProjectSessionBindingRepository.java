package com.fuquan.audit.contract.infra.memory;

import com.fuquan.audit.contract.application.session.ProjectSessionBindingRepository;
import com.fuquan.audit.contract.domain.ReviewSessionProjectFileBinding;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryProjectSessionBindingRepository implements ProjectSessionBindingRepository {
    private final Map<UUID, ReviewSessionProjectFileBinding> rows = new LinkedHashMap<>();
    @Override public synchronized <T> T withSessionFence(UUID id, Supplier<T> command) { return command.get(); }
    @Override public synchronized void insert(ReviewSessionProjectFileBinding binding) {
        if (rows.containsKey(binding.id()) || rows.values().stream().anyMatch(b -> b.sessionId().equals(binding.sessionId())
                && b.selectionVersion() == binding.selectionVersion() && (b.projectFileId().equals(binding.projectFileId()) || b.ordinal() == binding.ordinal())))
            throw new IllegalStateException("duplicate session binding");
        rows.put(binding.id(), binding);
    }
    @Override public synchronized List<ReviewSessionProjectFileBinding> findBySessionId(UUID id) {
        return rows.values().stream().filter(b -> b.sessionId().equals(id))
                .sorted(Comparator.comparingLong(ReviewSessionProjectFileBinding::selectionVersion)
                        .thenComparingInt(ReviewSessionProjectFileBinding::ordinal)).toList();
    }
    @Override public synchronized void deactivateBySessionId(UUID id) {
        rows.replaceAll((key, b) -> b.sessionId().equals(id) && b.activeFlag() ? b.deactivate() : b);
    }
}
