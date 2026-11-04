package com.fuquan.audit.integration.cache.memory;

import com.fuquan.audit.common.application.port.CachePort;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "local-dev"})
public class InMemoryCacheAdapter implements CachePort {

    private final ConcurrentMap<String, CacheEntry> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String key) {
        CacheEntry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAtNanos() <= System.nanoTime()) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void put(String key, String value, Duration timeToLive) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(timeToLive, "timeToLive must not be null");
        if (timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("timeToLive must be positive");
        }
        entries.put(key, new CacheEntry(value, System.nanoTime() + timeToLive.toNanos()));
    }

    @Override
    public void evict(String key) {
        entries.remove(key);
    }

    private record CacheEntry(String value, long expiresAtNanos) {
    }
}
