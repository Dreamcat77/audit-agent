package com.fuquan.audit.common.application.port;

import java.time.Duration;
import java.util.Optional;

public interface CachePort {

    Optional<String> get(String key);

    void put(String key, String value, Duration timeToLive);

    void evict(String key);
}
