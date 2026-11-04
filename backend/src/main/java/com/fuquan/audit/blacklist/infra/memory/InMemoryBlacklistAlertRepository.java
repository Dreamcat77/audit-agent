package com.fuquan.audit.blacklist.infra.memory;

import com.fuquan.audit.blacklist.application.port.BlacklistAlertRepository;
import com.fuquan.audit.blacklist.domain.BlacklistAlert;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryBlacklistAlertRepository implements BlacklistAlertRepository {

    private final List<BlacklistAlert> stored = new ArrayList<>();

    @Override
    public synchronized void save(BlacklistAlert alert) {
        stored.add(alert);
    }

    @Override
    public synchronized void saveAll(List<BlacklistAlert> alerts) {
        stored.addAll(alerts);
    }

    @Override
    public synchronized Optional<BlacklistAlert> findById(UUID id) {
        return stored.stream().filter(a -> a.id().equals(id)).findFirst();
    }

    @Override
    public synchronized List<BlacklistAlert> findAll(String status) {
        if (status == null || status.isBlank()) {
            return List.copyOf(stored);
        }
        return stored.stream().filter(a -> status.equals(a.status())).toList();
    }

    @Override
    public synchronized AlertCreation saveIfAbsent(BlacklistAlert alert) {
        Optional<BlacklistAlert> existing = stored.stream()
                .filter(item -> item.batchId().equals(alert.batchId()))
                .filter(item -> item.supplierName().equals(alert.supplierName()))
                .findFirst();
        if (existing.isPresent()) {
            return new AlertCreation(existing.get(), false);
        }
        stored.add(alert);
        return new AlertCreation(alert, true);
    }

    @Override
    public synchronized boolean updateIfStatus(BlacklistAlert alert, String expectedStatus) {
        for (int i = 0; i < stored.size(); i++) {
            if (stored.get(i).id().equals(alert.id())
                    && stored.get(i).status().equals(expectedStatus)) {
                stored.set(i, alert);
                return true;
            }
        }
        return false;
    }
}
