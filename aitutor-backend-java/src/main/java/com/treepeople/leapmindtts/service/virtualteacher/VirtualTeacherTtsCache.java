package com.treepeople.leapmindtts.service.virtualteacher;

import com.treepeople.leapmindtts.config.VirtualTeacherProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class VirtualTeacherTtsCache {
    private final StringRedisTemplate redis;
    private final VirtualTeacherProperties properties;
    private final Map<String, LocalEntry> localFallback = new ConcurrentHashMap<>();
    private final Clock clock;

    /** 本地缓存条目数达到该阈值时触发一次过期清理。 */
    private static final int CLEANUP_THRESHOLD = 128;

    /** 两次自动清理之间的最小间隔，避免高流量下频繁 O(n) 扫描。 */
    private static final Duration CLEANUP_MIN_INTERVAL = Duration.ofSeconds(30);

    private final AtomicLong lastCleanupAtMillis = new AtomicLong(0L);

    /** 包内测试 seam：cleanupExpired 在删除每个过期条目前调用，供并发回归测试注入同步点。 */
    volatile Runnable cleanupBeforeRemoveProbe;

    public VirtualTeacherTtsCache(
            ObjectProvider<StringRedisTemplate> redisProvider,
            VirtualTeacherProperties properties) {
        this(redisProvider, properties, Clock.systemDefaultZone());
    }

    /** 测试专用构造器：注入可控时钟，使过期清理可确定性验证，不影响生产行为。 */
    VirtualTeacherTtsCache(
            ObjectProvider<StringRedisTemplate> redisProvider,
            VirtualTeacherProperties properties,
            Clock clock) {
        this.redis = redisProvider.getIfAvailable();
        this.properties = properties;
        this.clock = clock;
    }

    public Optional<String> get(String key) {
        if (redis != null) {
            try {
                String value = redis.opsForValue().get(key);
                if (value != null) return Optional.of(value);
            } catch (RuntimeException error) {
                log.warn("Redis 不可用，使用进程内 TTS 缓存: {}", error.getMessage());
            }
        }
        LocalEntry entry = localFallback.get(key);
        if (entry == null || entry.expiresAt().isBefore(clock.instant())) {
            // 条件原子删除：仅当当前 mapping 仍是观察到的过期 entry 才删除，
            // 避免误删已被其他线程并发刷新为有效新 entry 的 mapping。
            localFallback.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.objectKey());
    }

    public void put(String key, String objectKey) {
        if (redis != null) {
            try {
                redis.opsForValue().set(key, objectKey, properties.getCacheTtl());
            } catch (RuntimeException error) {
                log.warn("写入 Redis 失败，保留进程内缓存: {}", error.getMessage());
            }
        }
        localFallback.put(key, new LocalEntry(
                objectKey,
                clock.instant().plus(properties.getCacheTtl())));
        maybeCleanupExpired();
    }

    /**
     * 有界、节流式过期清理：
     * 仅当本地条目数达到阈值且距上次清理超过最小间隔时触发，
     * 使用 CAS 保证同一时刻只有一个线程执行扫描；常规路径仍是 O(1)。
     */
    private void maybeCleanupExpired() {
        if (localFallback.size() < CLEANUP_THRESHOLD) {
            return;
        }
        long nowMillis = clock.millis();
        long last = lastCleanupAtMillis.get();
        if (nowMillis - last < CLEANUP_MIN_INTERVAL.toMillis()) {
            return;
        }
        if (lastCleanupAtMillis.compareAndSet(last, nowMillis)) {
            int removed = cleanupExpired();
            log.debug("本地 TTS 缓存过期清理完成，移除 {} 个过期条目，剩余 {} 个",
                    removed, localFallback.size());
        }
    }

    /** 移除所有已过期的本地条目，返回被移除的数量（测试可直接调用以验证清理行为）。 */
    int cleanupExpired() {
        Instant now = clock.instant();
        int removed = 0;
        for (Map.Entry<String, LocalEntry> entry : localFallback.entrySet()) {
            LocalEntry observed = entry.getValue();
            if (observed.expiresAt().isBefore(now)) {
                Runnable probe = cleanupBeforeRemoveProbe;
                if (probe != null) {
                    probe.run();
                }
                // 条件原子删除：仅当该 key 当前 mapping 仍是观察到的过期 entry 才删除，
                // 保证并发 refresh 后的新 entry 不会被旧 cleanup 误删。
                if (localFallback.remove(entry.getKey(), observed)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /** 当前本地缓存条目数（测试用于断言 Map 是否实际缩小）。 */
    int localFallbackSize() {
        return localFallback.size();
    }

    private record LocalEntry(String objectKey, Instant expiresAt) {
    }
}
