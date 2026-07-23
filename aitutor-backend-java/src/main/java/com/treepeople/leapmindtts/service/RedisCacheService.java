package com.treepeople.leapmindtts.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCacheService {
    private final RedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;

    private static final String NULL_PLACEHOLDER = "__NULL__";
    private static final Duration NULL_TTL = Duration.ofMinutes(5); // 空值防穿透拦截 5 分钟

    public String get(String cacheKey) {
        Object value = redisTemplate.opsForValue().get(cacheKey);
        if (value == null) {
            meterRegistry.counter("cache.misses", "cache", "redis").increment();
            return null;
        }

        // Using toString() might be risky if the object is not a String.
        // However, the serializer should handle it. Let's stick to the user's code.
        String strValue = value.toString();

        if (NULL_PLACEHOLDER.equals(strValue)) {
            meterRegistry.counter("cache.null.hits", "cache", "redis").increment();
            return null; // It's a null placeholder, so return actual null
        }

        meterRegistry.counter("cache.hits", "cache", "redis").increment();
        return strValue;
    }

    public void set(String cacheKey, String value, Duration ttl) {
        if (value == null) {
            setNullPlaceholder(cacheKey);
            return;
        }
        redisTemplate.opsForValue().set(cacheKey, value, ttl);
    }

    public void setNullPlaceholder(String cacheKey) {
        redisTemplate.opsForValue().set(cacheKey, NULL_PLACEHOLDER, NULL_TTL);
    }
}
