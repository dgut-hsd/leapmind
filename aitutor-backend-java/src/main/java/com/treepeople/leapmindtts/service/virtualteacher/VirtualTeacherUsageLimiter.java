package com.treepeople.leapmindtts.service.virtualteacher;

import com.treepeople.leapmindtts.config.VirtualTeacherProperties;
import com.treepeople.leapmindtts.exception.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class VirtualTeacherUsageLimiter {
    private final StringRedisTemplate redis;
    private final VirtualTeacherProperties properties;
    private final Map<String, LocalCounter> localCounters = new ConcurrentHashMap<>();
    private final Clock clock;

    /** 本地计数器条数达到该阈值时触发一次过期清理。 */
    private static final int CLEANUP_THRESHOLD = 128;

    /** 两次自动清理之间的最小间隔，避免高流量下频繁 O(n) 扫描。 */
    private static final Duration CLEANUP_MIN_INTERVAL = Duration.ofSeconds(30);

    private final AtomicLong lastCleanupAtMillis = new AtomicLong(0L);

    /** 包内测试 seam：cleanupExpired 在删除每个过期条目前调用，供并发回归测试注入同步点。 */
    volatile Runnable cleanupBeforeRemoveProbe;

    /**
     * 原子 INCRBY + 条件 PEXPIRE 脚本（固定单例，便于 Spring Data Redis 基于 SHA1 复用 script cache）。
     * <p>
     * 语义：
     * 1. 对 KEYS[1] 原子增加 ARGV[1]（amount，INCRBY 保持任意 amount 语义）；
     * 2. 若 current == amount，说明该 key 刚被首次创建/累计，设置 PEXPIRE ARGV[2]（ttl 毫秒）；
     * 3. 返回 current。
     * <p>
     * 该脚本把「增加 + 条件设置 TTL」合并为单个 Redis 原子操作，
     * 消除原实现中 increment 成功但 expire 未执行导致的 key 永久残留竞态。
     */
    private static final RedisScript<Long> INCREMENT_AND_EXPIRE_IF_FIRST =
            new DefaultRedisScript<>(
                    "local current = redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))\n"
                            + "if current == tonumber(ARGV[1]) then\n"
                            + "    redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]))\n"
                            + "end\n"
                            + "return current",
                    Long.class);

    public VirtualTeacherUsageLimiter(
            ObjectProvider<StringRedisTemplate> redisProvider,
            VirtualTeacherProperties properties) {
        this(redisProvider, properties, Clock.systemDefaultZone());
    }

    /** 测试专用构造器：注入可控时钟，使过期清理可确定性验证，不影响生产行为。 */
    VirtualTeacherUsageLimiter(
            ObjectProvider<StringRedisTemplate> redisProvider,
            VirtualTeacherProperties properties,
            Clock clock) {
        this.redis = redisProvider.getIfAvailable();
        this.properties = properties;
        this.clock = clock;
    }

    public void check(Long userId, int textLength) {
        VirtualTeacherProperties.RateLimit limit = properties.getRateLimit();
        if (!limit.isEnabled()) return;

        maybeCleanupExpired();

        String userKey = String.valueOf(userId);
        incrementOrReject(
                "virtual-teacher:rate:" + userKey + ":" + minuteBucket(),
                limit.getRequestsPerMinute(),
                1,
                Duration.ofMinutes(2),
                "语音合成请求过于频繁，请稍后再试");
        incrementOrReject(
                "virtual-teacher:chars:" + userKey + ":" + LocalDate.now(clock),
                limit.getDailyCharacters(),
                textLength,
                Duration.ofDays(2),
                "今日虚拟教师语音合成字符数已达上限");
    }

    private void incrementOrReject(String key, int limit, int amount, Duration ttl, String message) {
        if (limit <= 0) return;
        Long current = incrementRedis(key, amount, ttl);
        if (current == null) {
            current = incrementLocal(key, amount, ttl);
        }
        if (current > limit) {
            throw new TooManyRequestsException(message);
        }
    }

    private Long incrementRedis(String key, int amount, Duration ttl) {
        if (redis == null) return null;
        try {
            // 单次 Lua 脚本原子完成 INCRBY + 条件 PEXPIRE，
            // 避免 increment 成功后 expire 未执行导致的 key 残留竞态。
            // amount 经 ARGV 传入，保持任意累加语义（rate=1，daily chars=textLength）。
            return redis.execute(
                    INCREMENT_AND_EXPIRE_IF_FIRST,
                    Collections.singletonList(key),
                    String.valueOf(amount),
                    String.valueOf(ttl.toMillis()));
        } catch (RuntimeException error) {
            log.warn("Redis 限流不可用，使用进程内限流: {}", error.getMessage());
            return null;
        }
    }

    /**
     * 本地计数（包内可见，供并发回归测试直接刷新指定 key）。
     *
     * @return 累加后的计数
     */
    long incrementLocal(String key, int amount, Duration ttl) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalCounter counter = localCounters.compute(key, (ignored, current) -> {
            if (current == null || current.expiresAt().isBefore(now)) {
                return new LocalCounter(new AtomicInteger(amount), now.plus(ttl));
            }
            current.value.addAndGet(amount);
            return current;
        });
        return counter.value.get();
    }

    private String minuteBucket() {
        LocalDateTime now = LocalDateTime.now(clock);
        return now.getYear() + "-" + now.getDayOfYear() + "-" + now.getHour() + "-" + now.getMinute();
    }

    /**
     * 有界、节流式过期清理：
     * 仅当本地计数器条数达到阈值且距上次清理超过最小间隔时触发，
     * 使用 CAS 保证同一时刻只有一个线程执行扫描；常规路径仍是 O(1)。
     */
    private void maybeCleanupExpired() {
        if (localCounters.size() < CLEANUP_THRESHOLD) {
            return;
        }
        long nowMillis = clock.millis();
        long last = lastCleanupAtMillis.get();
        if (nowMillis - last < CLEANUP_MIN_INTERVAL.toMillis()) {
            return;
        }
        if (lastCleanupAtMillis.compareAndSet(last, nowMillis)) {
            int removed = cleanupExpired();
            log.debug("本地限流计数器过期清理完成，移除 {} 个过期条目，剩余 {} 个",
                    removed, localCounters.size());
        }
    }

    /** 移除所有已过期的本地计数器，返回被移除的数量（测试可直接调用以验证清理行为）。 */
    int cleanupExpired() {
        LocalDateTime now = LocalDateTime.now(clock);
        int removed = 0;
        for (Map.Entry<String, LocalCounter> entry : localCounters.entrySet()) {
            LocalCounter observed = entry.getValue();
            if (observed.expiresAt().isBefore(now)) {
                Runnable probe = cleanupBeforeRemoveProbe;
                if (probe != null) {
                    probe.run();
                }
                // 条件原子删除：仅当该 key 当前 mapping 仍是观察到的过期计数器才删除，
                // 保证并发 compute 刷新后的新计数器不会被旧 cleanup 误删。
                if (localCounters.remove(entry.getKey(), observed)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /** 当前本地计数器条数（测试用于断言 Map 是否实际缩小）。 */
    int localCountersSize() {
        return localCounters.size();
    }

    private record LocalCounter(AtomicInteger value, LocalDateTime expiresAt) {
    }
}
