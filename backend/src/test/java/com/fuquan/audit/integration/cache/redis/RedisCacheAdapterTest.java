package com.fuquan.audit.integration.cache.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisCacheAdapterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisCacheAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new RedisCacheAdapter(redisTemplate, "audit-agent:");
    }

    @Test
    void getUsesNamespaceAndReturnsValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("audit-agent:task:1")).thenReturn("PENDING");

        assertThat(adapter.get("task:1")).contains("PENDING");
    }

    @Test
    void getReturnsEmptyWhenRedisHasNoValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        assertThat(adapter.get("missing")).isEmpty();
    }

    @Test
    void putUsesSetWithPositiveTtlAndNamespace() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Duration ttl = Duration.ofMinutes(5);

        adapter.put("task:1", "DONE", ttl);

        verify(valueOperations).set("audit-agent:task:1", "DONE", ttl);
    }

    @Test
    void evictUsesDeleteAndNamespace() {
        adapter.evict("task:1");

        verify(redisTemplate).delete("audit-agent:task:1");
    }

    @Test
    void rejectsNullOrBlankKey() {
        assertThatThrownBy(() -> adapter.get(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key must not be null");
        assertThatThrownBy(() -> adapter.evict("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("key must not be blank");
    }

    @Test
    void rejectsNullValueAndInvalidTtl() {
        assertThatThrownBy(() -> adapter.put("task:1", null, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("value must not be null");
        assertThatThrownBy(() -> adapter.put("task:1", "DONE", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("timeToLive must be positive");
        assertThatThrownBy(() -> adapter.put("task:1", "DONE", Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("timeToLive must be positive");
        assertThatThrownBy(() -> adapter.put("task:1", "DONE", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("timeToLive must not be null");
    }
}
