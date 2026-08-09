package com.treepeople.leapmindtts.service.virtualteacher;

import com.treepeople.leapmindtts.config.VirtualTeacherProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VirtualTeacherTtsCacheTest {

    /** 可手动拨动的测试时钟，避免用 sleep 等待 TTL 过期。 */
    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<StringRedisTemplate> noRedis() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private VirtualTeacherProperties properties;
    private MutableClock clock;
    private VirtualTeacherTtsCache cache;

    @BeforeEach
    void setUp() {
        properties = new VirtualTeacherProperties();
        properties.setCacheTtl(Duration.ofHours(1));
        clock = new MutableClock(Instant.parse("2026-08-08T00:00:00Z"));
        cache = new VirtualTeacherTtsCache(noRedis(), properties, clock);
    }

    @Test
    void returnsValidLocalEntryBeforeExpiry() {
        cache.put("tts:audio:k1", "k1.wav");

        Optional<String> value = cache.get("tts:audio:k1");

        assertTrue(value.isPresent());
        assertEquals("k1.wav", value.get());
    }

    @Test
    void expiredLocalEntryIsNotReturnedAndIsRemoved() {
        cache.put("tts:audio:k1", "k1.wav");
        clock.advance(Duration.ofHours(2)); // 超过 1h TTL

        Optional<String> value = cache.get("tts:audio:k1");

        assertTrue(value.isEmpty());
        assertEquals(0, cache.localFallbackSize());
    }

    @Test
    void cleanupRemovesMultipleExpiredEntries() {
        cache.put("tts:audio:k1", "k1.wav");
        cache.put("tts:audio:k2", "k2.wav");
        cache.put("tts:audio:k3", "k3.wav");
        clock.advance(Duration.ofHours(2)); // 全部过期

        int removed = cache.cleanupExpired();

        assertEquals(3, removed);
        assertEquals(0, cache.localFallbackSize());
        assertTrue(cache.get("tts:audio:k1").isEmpty());
        assertTrue(cache.get("tts:audio:k2").isEmpty());
        assertTrue(cache.get("tts:audio:k3").isEmpty());
    }

    @Test
    void cleanupKeepsValidEntries() {
        cache.put("tts:audio:k1", "k1.wav"); // t0，1h 后过期
        clock.advance(Duration.ofMinutes(30));
        cache.put("tts:audio:k2", "k2.wav"); // t30，1h 后过期
        clock.advance(Duration.ofMinutes(31)); // t61：k1 已过期，k2 剩 59 分钟

        int removed = cache.cleanupExpired();

        assertEquals(1, removed);
        assertEquals(1, cache.localFallbackSize());
        assertTrue(cache.get("tts:audio:k1").isEmpty());
        assertEquals("k2.wav", cache.get("tts:audio:k2").orElseThrow());
    }

    @Test
    void localFallbackWorksWhenRedisUnavailable() {
        cache.put("tts:audio:k1", "k1.wav");

        assertEquals("k1.wav", cache.get("tts:audio:k1").orElseThrow());
    }

    @Test
    void automaticCleanupTriggeredByPut() {
        // 达到 CLEANUP_THRESHOLD(128) 后再 put，必须由 put 内部自动清理过期条目
        for (int i = 0; i < 130; i++) {
            cache.put("tts:audio:bulk" + i, "b" + i + ".wav");
        }
        assertEquals(130, cache.localFallbackSize());
        clock.advance(Duration.ofHours(2)); // 全部过期

        cache.put("tts:audio:trigger", "trigger.wav"); // 触发 maybeCleanupExpired

        assertEquals(1, cache.localFallbackSize(), "put 必须自动清理已过期条目");
        assertEquals("trigger.wav", cache.get("tts:audio:trigger").orElseThrow());
        assertTrue(cache.get("tts:audio:bulk0").isEmpty());
    }

    @Test
    void entryExpiringExactlyAtNowIsStillValid() {
        // 历史语义：expiresAt.isBefore(now)，expiresAt == now 仍视为有效
        cache.put("tts:audio:k1", "k1.wav");
        clock.advance(Duration.ofHours(1)); // expiresAt == now

        assertEquals("k1.wav", cache.get("tts:audio:k1").orElseThrow());
        assertEquals(0, cache.cleanupExpired());
        assertEquals(1, cache.localFallbackSize());
    }

    @Test
    void concurrentRefreshSurvivesCleanup() throws Exception {
        cache.put("tts:audio:K", "old.wav");
        clock.advance(Duration.ofHours(2)); // K 过期
        CountDownLatch observed = new CountDownLatch(1);
        CountDownLatch refreshed = new CountDownLatch(1);
        cache.cleanupBeforeRemoveProbe = () -> {
            observed.countDown();
            try {
                assertTrue(refreshed.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Thread cleaner = new Thread(() -> cache.cleanupExpired(), "cleanup");
        cleaner.start();

        assertTrue(observed.await(5, TimeUnit.SECONDS)); // cleanup 已观察到旧过期条目并暂停
        cache.put("tts:audio:K", "new.wav"); // 并发刷新为有效新条目
        refreshed.countDown();
        cleaner.join(5_000);
        assertFalse(cleaner.isAlive());

        // 刚刷新为有效的新 entry 不得被 cleanup 删除
        assertEquals("new.wav", cache.get("tts:audio:K").orElseThrow());
        assertEquals(1, cache.localFallbackSize());
    }
}
