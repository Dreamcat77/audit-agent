package com.fuquan.audit.blacklist.infra.memory;

import com.fuquan.audit.blacklist.application.port.BlacklistSupplierRepository;
import com.fuquan.audit.blacklist.domain.BlacklistSupplier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local")
public class InMemoryBlacklistSupplierRepository implements BlacklistSupplierRepository {

    private final List<BlacklistSupplier> stored = new ArrayList<>();

    @Override
    public List<BlacklistSupplier> findAllActive() {
        return stored.stream()
                .filter(BlacklistSupplier::active)
                .toList();
    }

    @Override
    public List<BlacklistSupplier> findAll() {
        return Collections.unmodifiableList(stored);
    }

    @Override
    public BlacklistSupplier save(BlacklistSupplier supplier) {
        stored.removeIf(item -> item.id().equals(supplier.id()));
        stored.add(supplier);
        return supplier;
    }

    @Override
    public BlacklistSupplier update(BlacklistSupplier supplier) {
        return save(supplier);
    }

    @Override
    public void deleteById(UUID id) {
        stored.replaceAll(s -> s.id().equals(id) ? s.deactivate() : s);
    }

    public List<BlacklistSupplier> stored() {
        return Collections.unmodifiableList(stored);
    }
}
