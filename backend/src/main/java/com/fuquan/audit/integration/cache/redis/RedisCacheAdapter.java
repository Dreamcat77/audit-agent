package com.fuquan.audit.integration.cache.redis;

import com.fuquan.audit.common.application.port.CachePort;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile({"dev29", "customer"})
public class RedisCacheAdapter implements CachePort {

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public RedisCacheAdapter(
            StringRedisTemplate redisTemplate,
            @Value("${audit.cache.key-prefix:audit-agent:}") String keyPrefix) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        if (keyPrefix.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        this.keyPrefix = keyPrefix.endsWith(":") ? keyPrefix : keyPrefix + ":";
    }

    @Override
    public Optional<String> get(String key) {
        String namespacedKey = namespaced(key);
        return Optional.ofNullable(redisTemplate.opsForValue().get(namespacedKey));
    }

    @Override
    public void put(String key, String value, Duration timeToLive) {
        String namespacedKey = namespaced(key);
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(timeToLive, "timeToLive must not be null");
        if (timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("timeToLive must be positive");
        }
        redisTemplate.opsForValue().set(namespacedKey, value, timeToLive);
    }

    @Override
    public void evict(String key) {
        redisTemplate.delete(namespaced(key));
    }

    private String namespaced(String key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        return keyPrefix + key;
    }
}
