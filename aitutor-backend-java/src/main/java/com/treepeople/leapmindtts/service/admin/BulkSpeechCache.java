package com.treepeople.leapmindtts.service.admin;

import com.treepeople.leapmindtts.exception.BulkSpeechRateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批量语音合成 Redis 缓存与限流组件
 *
 * <p>内置三种经典缓存问题的防护：
 *
 * <h3>缓存穿透（Penetration）</h3>
 * 查询不存在的数据导致请求直达后端。
 * 防护：TTS 合成失败的文本写入 null 标记（{@code __NULL__}），
 * 短 TTL（5 分钟），避免重复调用 TTS API 攻击后端。
 *
 * <h3>缓存击穿（Hot-Key Breakdown）</h3>
 * 热点 key 过期瞬间大量并发请求同时回源。
 * 防护：Redis SETNX 互斥锁，保证同一时刻只有一个线程重建缓存，
 * 其余线程自旋等待（最多 200ms × 10 次）后降级返回。
 *
 * <h3>缓存雪崩（Avalanche）</h3>
 * 大量 key 同时过期导致后端过载。
 * 防护：
 * <ol>
 *   <li>TTL 随机抖动 — 基值 ± 20%，打散过期时间窗口</li>
 *   <li>L1 本地缓存 — ConcurrentHashMap 作为进程内一级缓存，
 *       即使 Redis 全量过期也不会同时击穿 DB/TTS</li>
 * </ol>
 *
 * <p>复用项目已有的 Redis 成熟设计模式：
 * <ul>
 *   <li>ObjectProvider 延迟注入 — Redis 不可用时不影响应用启动</li>
 *   <li>进程内 ConcurrentHashMap 回退 — Redis 不可用时降级到本地</li>
 *   <li>完善的异常处理 — 所有 Redis 操作 catch RuntimeException</li>
 * </ul>
 */
@Slf4j
@Component
public class BulkSpeechCache {

    private final StringRedisTemplate redis;
    private final boolean cacheEnabled;
    private final boolean rateLimitEnabled;
    private final int requestsPerMinute;
    private final int dailyCharacters;

    // 进程内回退缓存（同时作为 L1 缓存，防雪崩）
    private final Map<String, LocalAudioEntry> localAudioCache = new ConcurrentHashMap<>();
    private final Map<String, LocalCounter> localCounters = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyEntry> localIdempotency = new ConcurrentHashMap<>();

    // 缓存穿透：TTS 失败的 null 标记
    private static final String NULL_MARKER = "__NULL__";
    private static final Duration NULL_MARKER_TTL = Duration.ofMinutes(5);

    // 缓存雪崩：TTL 抖动系数
    private static final double TTL_JITTER_RATIO = 0.20;

    // 缓存击穿：互斥锁配置
    private static final String LOCK_KEY_PREFIX = "bulk-speech:lock:build:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final int SPIN_MAX_RETRIES = 10;
    private static final long SPIN_BASE_WAIT_MS = 50;
    private static final long SPIN_MAX_WAIT_MS = 500;

    private static final Duration AUDIO_CACHE_TTL = Duration.ofHours(24);
    private static final Duration RATE_LIMIT_MINUTE_TTL = Duration.ofMinutes(2);
    private static final Duration RATE_LIMIT_DAILY_TTL = Duration.ofDays(2);
    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(30);

    private static final String KEY_PREFIX = "bulk-speech:";
    private static final String AUDIO_KEY = KEY_PREFIX + "audio:";
    private static final String RATE_MINUTE_KEY = KEY_PREFIX + "rate:min:";
    private static final String RATE_DAILY_KEY = KEY_PREFIX + "rate:chars:";
    private static final String IDEMPOTENCY_KEY = KEY_PREFIX + "idem:";

    private final SecureRandom random = new SecureRandom();

    public BulkSpeechCache(
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${bulk-speech.cache.enabled:true}") boolean cacheEnabled,
            @Value("${bulk-speech.rate-limit.enabled:true}") boolean rateLimitEnabled,
            @Value("${bulk-speech.rate-limit.requests-per-minute:30}") int requestsPerMinute,
            @Value("${bulk-speech.rate-limit.daily-characters:50000}") int dailyCharacters) {
        this.redis = redisProvider.getIfAvailable();
        this.cacheEnabled = cacheEnabled;
        this.rateLimitEnabled = rateLimitEnabled;
        this.requestsPerMinute = requestsPerMinute;
        this.dailyCharacters = dailyCharacters;
    }

    // ==================== TTS 音频缓存（含穿透/击穿/雪崩防护） ====================

    /**
     * 获取缓存的音频数据。
     *
     * <p><b>击穿防护</b>：如果 key 过期但未命中，调用方应调用 {@link #getCachedAudioWithRebuildLock}
     * 获取带互斥锁的版本，避免多个线程同时回源。
     *
     * @param textHash 文本哈希（SHA-256）
     * @return 缓存的音频字节数组；{@code null} 表示未命中（含穿透标记命中）
     */
    public byte[] getCachedAudio(String textHash) {
        if (!cacheEnabled) return null;

        String key = AUDIO_KEY + textHash;

        // ── 一级缓存：进程内 L1（防雪崩） ──
        LocalAudioEntry l1Entry = localAudioCache.get(key);
        if (l1Entry != null && l1Entry.expiresAt().isAfter(LocalDateTime.now())) {
            if (l1Entry.isNull()) {
                log.debug("TTS 穿透标记命中 (L1), textHash: {}", textHash);
                return null;
            }
            log.debug("TTS 缓存命中 (L1), textHash: {}", textHash);
            return l1Entry.audioData();
        }
        localAudioCache.remove(key);

        // ── 二级缓存：Redis ──
        if (redis != null) {
            try {
                String value = redis.opsForValue().get(key);
                if (value != null) {
                    if (NULL_MARKER.equals(value)) {
                        // 穿透标记命中 — 该文本 TTS 合成已知失败
                        log.debug("TTS 穿透标记命中 (Redis), textHash: {}", textHash);
                        // 回写到 L1
                        localAudioCache.put(key, LocalAudioEntry.nullEntry(NULL_MARKER_TTL));
                        return null;
                    }
                    log.debug("TTS 缓存命中 (Redis), textHash: {}", textHash);
                    byte[] audioData = java.util.Base64.getDecoder().decode(value);
                    // 回写到 L1
                    localAudioCache.put(key, new LocalAudioEntry(audioData, AUDIO_CACHE_TTL, false));
                    return audioData;
                }
            } catch (RuntimeException e) {
                log.warn("Redis 不可用，降级到 L1: {}", e.getMessage());
            }
        }

        return null;
    }

    /**
     * 带互斥锁重建的缓存获取（击穿防护）。
     *
     * <p>当 {@link #getCachedAudio} 返回 null 时调用此方法。
     * 使用 Redis SETNX 互斥锁保证只有一个线程回源 TTS API，
     * 其余线程自旋等待重建完成后从缓存读取。
     *
     * <p><b>注意</b>：如果获取到锁（返回 true），调用方负责：
     * <ol>
     *   <li>调用 TTS API 合成音频</li>
     *   <li>成功后调用 {@link #putCachedAudio(String, byte[])}
     *       或失败后调用 {@link #putCachedAudioNull(String)}</li>
     *   <li>调用 {@link #releaseRebuildLock(String)} 释放锁</li>
     * </ol>
     *
     * @param textHash 文本哈希
     * @return {@code true} 表示获取到互斥锁，应由调用方重建缓存；
     *         {@code false} 表示其他线程正在重建，调用方应重试 getCachedAudio
     */
    public boolean tryAcquireRebuildLock(String textHash) {
        if (redis == null) {
            // 无 Redis，由调用方直接重建（L1 非共享，每个 JVM 各建一份）
            return true;
        }

        String lockKey = LOCK_KEY_PREFIX + textHash;
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                log.debug("获取缓存重建锁成功, textHash: {}", textHash);
                return true;
            }
            log.debug("缓存重建锁已被其他线程持有, textHash: {}", textHash);
            return false;
        } catch (RuntimeException e) {
            log.warn("Redis 互斥锁操作失败，允许直接重建: {}", e.getMessage());
            return true; // Redis 不可用时退化为无锁重建
        }
    }

    /**
     * 释放缓存重建互斥锁。
     */
    public void releaseRebuildLock(String textHash) {
        if (redis == null) return;
        String lockKey = LOCK_KEY_PREFIX + textHash;
        try {
            redis.delete(lockKey);
        } catch (RuntimeException e) {
            log.warn("释放缓存重建锁失败: {}", e.getMessage());
        }
    }

    /**
     * 自旋等待缓存重建完成。
     *
     * <p>当 {@link #tryAcquireRebuildLock} 返回 false 时调用，指数退避最多重试
     * {@link #SPIN_MAX_RETRIES} 次，之后 getCachedAudio 应可命中。
     *
     * @param textHash 文本哈希
     * @return 自旋后命中的缓存数据，超时返回 null
     */
    public byte[] spinWaitForRebuild(String textHash) {
        for (int i = 0; i < SPIN_MAX_RETRIES; i++) {
            try {
                long waitMs = Math.min(SPIN_BASE_WAIT_MS * (1L << i), SPIN_MAX_WAIT_MS);
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }

            byte[] audio = getCachedAudio(textHash);
            if (audio != null) {
                log.debug("自旋等待后缓存命中, textHash: {}, 等待次数: {}", textHash, i + 1);
                return audio;
            }

            // 检查是否有穿透标记（TTS 失败）
            if (isNullMarkerCached(textHash)) {
                log.debug("自旋等待检测到穿透标记, textHash: {}", textHash);
                return null;
            }
        }
        log.warn("自旋等待重建超时, textHash: {}", textHash);
        return null;
    }

    /**
     * 缓存音频数据（含雪崩防护：TTL 随机抖动）。
     *
     * @param textHash  文本哈希
     * @param audioData 音频字节数组
     */
    public void putCachedAudio(String textHash, byte[] audioData) {
        if (!cacheEnabled || audioData == null || audioData.length == 0) return;

        String key = AUDIO_KEY + textHash;
        String encoded = java.util.Base64.getEncoder().encodeToString(audioData);
        Duration jitteredTtl = jitterTtl(AUDIO_CACHE_TTL);

        if (redis != null) {
            try {
                redis.opsForValue().set(key, encoded, jitteredTtl);
            } catch (RuntimeException e) {
                log.warn("写入 Redis 失败，保留 L1 缓存: {}", e.getMessage());
            }
        }

        // L1 缓存（防雪崩：本地缓存 TTL 同样抖动）
        localAudioCache.put(key, new LocalAudioEntry(audioData, jitteredTtl, false));
        log.debug("TTS 缓存写入, textHash: {}, TTL: {}s", textHash, jitteredTtl.toSeconds());
    }

    /**
     * 【穿透防护】缓存 TTS 合成失败的 null 标记。
     *
     * <p>对明确合成失败的文本写入短 TTL 标记，避免短时间内大量重复请求打到 TTS API。
     *
     * @param textHash 文本哈希
     */
    public void putCachedAudioNull(String textHash) {
        if (!cacheEnabled) return;

        String key = AUDIO_KEY + textHash;
        Duration jitteredTtl = jitterTtl(NULL_MARKER_TTL);

        if (redis != null) {
            try {
                redis.opsForValue().set(key, NULL_MARKER, jitteredTtl);
            } catch (RuntimeException e) {
                log.warn("写入 Redis null 标记失败: {}", e.getMessage());
            }
        }

        localAudioCache.put(key, LocalAudioEntry.nullEntry(jitteredTtl));
        log.debug("TTS 穿透标记写入, textHash: {}, TTL: {}s", textHash, jitteredTtl.toSeconds());
    }

    /**
     * 检查指定 key 是否有穿透标记（TTS 失败缓存）。
     */
    private boolean isNullMarkerCached(String textHash) {
        String key = AUDIO_KEY + textHash;

        // 检查 L1
        LocalAudioEntry l1Entry = localAudioCache.get(key);
        if (l1Entry != null && l1Entry.expiresAt().isAfter(LocalDateTime.now()) && l1Entry.isNull()) {
            return true;
        }

        // 检查 Redis
        if (redis != null) {
            try {
                String value = redis.opsForValue().get(key);
                return NULL_MARKER.equals(value);
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    // ==================== 速率限制 ====================

    public void checkRateLimit(Long userId, int textLength) {
        if (!rateLimitEnabled || userId == null) return;

        String userKey = String.valueOf(userId);

        checkLimit(
                RATE_MINUTE_KEY + userKey + ":" + minuteBucket(),
                requestsPerMinute,
                1,
                RATE_LIMIT_MINUTE_TTL,
                "批量语音合成请求过于频繁，请稍后再试");

        checkLimit(
                RATE_DAILY_KEY + userKey + ":" + LocalDate.now(),
                dailyCharacters,
                textLength,
                RATE_LIMIT_DAILY_TTL,
                "今日批量语音合成字符数已达上限");
    }

    private void checkLimit(String key, int limit, int amount, Duration ttl, String message) {
        if (limit <= 0) return;

        Long current = incrementRedis(key, amount, ttl);
        if (current == null) {
            current = incrementLocal(key, amount, ttl);
        }
        if (current > limit) {
            throw new BulkSpeechRateLimitException(message);
        }
    }

    private Long incrementRedis(String key, int amount, Duration ttl) {
        if (redis == null) return null;
        try {
            Long current = redis.opsForValue().increment(key, amount);
            if (current != null && current == amount) {
                redis.expire(key, ttl);
            }
            return current;
        } catch (RuntimeException e) {
            log.warn("Redis 限流不可用，使用进程内限流: {}", e.getMessage());
            return null;
        }
    }

    private long incrementLocal(String key, int amount, Duration ttl) {
        LocalDateTime now = LocalDateTime.now();
        LocalCounter counter = localCounters.compute(key, (ignored, current) -> {
            if (current == null || current.expiresAt().isBefore(now)) {
                return new LocalCounter(new AtomicInteger(amount), now.plus(ttl));
            }
            current.value.addAndGet(amount);
            return current;
        });
        return counter.value.get();
    }

    // ==================== 幂等控制 ====================

    public String tryAcquireIdempotency(String requestHash) {
        String key = IDEMPOTENCY_KEY + requestHash;

        if (redis != null) {
            try {
                String existing = redis.opsForValue().get(key);
                if (existing != null) {
                    log.info("幂等检查命中 (Redis), requestHash: {}, courseId: {}", requestHash, existing);
                    return existing;
                }
                Boolean acquired = redis.opsForValue().setIfAbsent(key, "PROCESSING", IDEMPOTENCY_TTL);
                if (Boolean.FALSE.equals(acquired)) {
                    existing = redis.opsForValue().get(key);
                    return existing;
                }
                return null;
            } catch (RuntimeException e) {
                log.warn("Redis 幂等检查不可用，使用进程内幂等: {}", e.getMessage());
            }
        }

        IdempotencyEntry entry = localIdempotency.get(key);
        if (entry != null && entry.expiresAt().isAfter(LocalDateTime.now())) {
            log.info("幂等检查命中 (本地), requestHash: {}, courseId: {}", requestHash, entry.courseId());
            return entry.courseId();
        }
        localIdempotency.put(key, new IdempotencyEntry("PROCESSING", LocalDateTime.now().plus(IDEMPOTENCY_TTL)));
        return null;
    }

    public void updateIdempotency(String requestHash, String courseId) {
        String key = IDEMPOTENCY_KEY + requestHash;

        if (redis != null) {
            try {
                redis.opsForValue().set(key, courseId, IDEMPOTENCY_TTL);
            } catch (RuntimeException e) {
                log.warn("Redis 更新幂等状态失败: {}", e.getMessage());
            }
        }

        localIdempotency.put(key, new IdempotencyEntry(courseId, LocalDateTime.now().plus(IDEMPOTENCY_TTL)));
    }

    public void releaseIdempotency(String requestHash) {
        String key = IDEMPOTENCY_KEY + requestHash;

        if (redis != null) {
            try {
                redis.delete(key);
            } catch (RuntimeException e) {
                log.warn("Redis 释放幂等锁失败: {}", e.getMessage());
            }
        }

        localIdempotency.remove(key);
    }

    // ==================== 雪崩防护：TTL 随机抖动 ====================

    /**
     * 在基值 TTL 上叠加 ±20% 的随机抖动，打散过期时间窗口防止雪崩。
     *
     * @param base 基础 TTL
     * @return 抖动后的 TTL（至少保留基础值的 80%）
     */
    Duration jitterTtl(Duration base) {
        long baseSeconds = base.toSeconds();
        long jitter = (long) (baseSeconds * TTL_JITTER_RATIO);
        long offset = (long) (random.nextDouble() * 2 * jitter) - jitter; // [-jitter, +jitter)
        long result = baseSeconds + offset;
        // 保底：不低于基础值的 80%
        return Duration.ofSeconds(Math.max(result, (long) (baseSeconds * 0.8)));
    }

    // ==================== 工具方法 ====================

    private String minuteBucket() {
        LocalDateTime now = LocalDateTime.now();
        return now.getYear() + "-" + now.getDayOfYear() + "-" + now.getHour() + "-" + now.getMinute();
    }

    public static String textHash(String text) {
        if (text == null || text.isEmpty()) return "";
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return String.valueOf(text.hashCode());
        }
    }

    // ==================== 内部记录类 ====================

    private static class LocalAudioEntry {
        private final byte[] audioData;
        private final LocalDateTime expiresAt;
        private final boolean isNull;

        LocalAudioEntry(byte[] audioData, Duration ttl, boolean isNull) {
            this.audioData = audioData;
            this.expiresAt = LocalDateTime.now().plus(ttl);
            this.isNull = isNull;
        }

        LocalAudioEntry(byte[] audioData, LocalDateTime expiresAt, boolean isNull) {
            this.audioData = audioData;
            this.expiresAt = expiresAt;
            this.isNull = isNull;
        }

        static LocalAudioEntry nullEntry(Duration ttl) {
            return new LocalAudioEntry(null, ttl, true);
        }

        byte[] audioData() { return audioData; }
        LocalDateTime expiresAt() { return expiresAt; }
        boolean isNull() { return isNull; }
    }

    private record LocalCounter(AtomicInteger value, LocalDateTime expiresAt) {
    }

    private record IdempotencyEntry(String courseId, LocalDateTime expiresAt) {
    }
}
