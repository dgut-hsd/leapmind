package com.treepeople.leapmindtts.service.virtualteacher;

import com.treepeople.leapmindtts.config.VirtualTeacherProperties;
import com.treepeople.leapmindtts.exception.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VirtualTeacherUsageLimiterTest {

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
    private VirtualTeacherUsageLimiter limiter;

    @BeforeEach
    void setUp() {
        properties = new VirtualTeacherProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setRequestsPerMinute(2);
        properties.getRateLimit().setDailyCharacters(100);
        clock = new MutableClock(Instant.parse("2026-08-08T00:00:00Z"));
        limiter = new VirtualTeacherUsageLimiter(noRedis(), properties, clock);
    }

    @Test
    void localRequestLimitCountsWhenRedisUnavailable() {
        limiter.check(42L, 5); // 第 1 次
        limiter.check(42L, 5); // 第 2 次，等于 limit=2，未超限

        assertThrows(TooManyRequestsException.class, () -> limiter.check(42L, 5)); // 第 3 次超限
    }

    @Test
    void throwsTooManyRequestsWhenExceedingDailyCharacterLimit() {
        properties.getRateLimit().setRequestsPerMinute(1000); // 排除分钟限流的干扰
        properties.getRateLimit().setDailyCharacters(50);
        limiter.check(42L, 30); // 30 <= 50

        assertThrows(TooManyRequestsException.class, () -> limiter.check(42L, 30)); // 60 > 50
    }

    @Test
    void dailyCharacterLimitCountsNormally() {
        properties.getRateLimit().setRequestsPerMinute(1000); // 排除分钟限流的干扰
        properties.getRateLimit().setDailyCharacters(50);
        limiter.check(42L, 20); // 20
        limiter.check(42L, 20); // 40

        assertThrows(TooManyRequestsException.class, () -> limiter.check(42L, 20)); // 60 > 50
    }

    @Test
    void cleanupRemovesExpiredMinuteBucketsAndKeepsFreshBucket() {
        limiter.check(42L, 5); // t0：rate bucket A（TTL 2min）+ daily bucket
        clock.advance(Duration.ofMinutes(3)); // bucket A 已过期，daily 未过期
        limiter.check(42L, 5); // t3：创建新 rate bucket B，daily 累加
        assertEquals(3, limiter.localCountersSize()); // rate:A + rate:B + daily

        int removed = limiter.cleanupExpired();

        assertEquals(1, removed); // 仅旧 minute bucket 被移除
        assertEquals(2, limiter.localCountersSize()); // 新 bucket + daily 保留
    }

    @Test
    void cleanupKeepsCurrentValidBucketCounting() {
        limiter.check(42L, 10); // t0：rate bucket A 计数 1，daily 计数 10
        clock.advance(Duration.ofSeconds(30)); // 未跨分钟边界、未过 TTL
        limiter.cleanupExpired();

        assertEquals(2, limiter.localCountersSize()); // 无过期条目被移除
        limiter.check(42L, 10); // 同一 bucket，计数 2
        assertThrows(TooManyRequestsException.class, () -> limiter.check(42L, 10)); // 计数 3 > 2 超限
    }

    @Test
    void automaticCleanupTriggeredByCheck() {
        // 65 个用户 × 2 key = 130 条，达到 CLEANUP_THRESHOLD(128)
        for (int i = 1; i <= 65; i++) {
            limiter.check((long) i, 5);
        }
        assertEquals(130, limiter.localCountersSize());
        clock.advance(Duration.ofMinutes(3)); // 65 个 rate bucket 过期，daily 未过期

        limiter.check(66L, 5); // 触发 maybeCleanupExpired：应自动移除 65 个过期 rate bucket

        assertEquals(67, limiter.localCountersSize(), "check 必须自动清理过期 minute bucket（65 rate + 用户66 的 rate/chars）");
    }

    @Test
    void counterExpiringExactlyAtNowStillCounts() {
        // 历史语义：expiresAt.isBefore(now)，expiresAt == now 仍视为有效。
        // 生产 key（rate=分钟粒度/1min、chars=日粒度/1day）无法在“键不变”前提下命中
        // expiresAt == now（TTL 2min/2day 都会跨桶），故用稳定 key 直接验证边界语义。
        String key = "virtual-teacher:rate:test:boundary";
        limiter.incrementLocal(key, 1, Duration.ofMinutes(2)); // t0 创建，expiresAt = t0+2min
        clock.advance(Duration.ofMinutes(2)); // expiresAt == now

        Long after = limiter.incrementLocal(key, 1, Duration.ofMinutes(2));

        // 若按“过期”处理会重建为 1；按“仍有效”处理则累加为 2
        assertEquals(2L, after);
    }

    @Test
    void concurrentRefreshSurvivesCleanup() throws Exception {
        String key = "virtual-teacher:rate:test:concurrent";
        limiter.incrementLocal(key, 1, Duration.ofMinutes(2)); // t0 创建
        clock.advance(Duration.ofMinutes(3)); // 过期
        CountDownLatch observed = new CountDownLatch(1);
        CountDownLatch refreshed = new CountDownLatch(1);
        limiter.cleanupBeforeRemoveProbe = () -> {
            observed.countDown();
            try {
                assertTrue(refreshed.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Thread cleaner = new Thread(() -> limiter.cleanupExpired(), "cleanup");
        cleaner.start();

        assertTrue(observed.await(5, TimeUnit.SECONDS)); // cleanup 已观察到旧过期计数器并暂停
        Long fresh = limiter.incrementLocal(key, 1, Duration.ofMinutes(2)); // 并发刷新为有效新计数器
        assertEquals(1L, fresh);
        refreshed.countDown();
        cleaner.join(5_000);
        assertFalse(cleaner.isAlive());

        // 刚刷新为有效的新计数器不得被 cleanup 删除：若被误删会重建为 1，保留则累加为 2
        Long after = limiter.incrementLocal(key, 1, Duration.ofMinutes(2));
        assertEquals(2L, after);
    }

    @Test
    void expiredLocalCountersCleanedAfterRedisRecovers() {
        // 构造一个运行时抛异常的 mock Redis，模拟 Redis 宕机后恢复
        // （Phase 3.2：Redis 交互走脚本 execute，mock seam 随之更新，行为断言不变）
        AtomicBoolean redisDown = new AtomicBoolean(true);
        StringRedisTemplate mockRedis = redisMock();
        when(mockRedis.execute(any(RedisScript.class), anyList(), any(), any())).thenAnswer(inv -> {
            if (redisDown.get()) {
                throw new RuntimeException("Redis 不可用");
            }
            return 1L;
        });

        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mockRedis);

        VirtualTeacherProperties props = new VirtualTeacherProperties();
        props.getRateLimit().setEnabled(true);
        props.getRateLimit().setRequestsPerMinute(1000);
        props.getRateLimit().setDailyCharacters(100000);

        MutableClock testClock = new MutableClock(Instant.parse("2026-08-08T00:00:00Z"));
        VirtualTeacherUsageLimiter limiterWithRedis = new VirtualTeacherUsageLimiter(provider, props, testClock);

        // Phase 1: Redis 抛异常，65 个用户 × 2 key = 130 个本地 fallback counter
        for (int i = 1; i <= 65; i++) {
            limiterWithRedis.check((long) i, 5);
        }
        assertEquals(130, limiterWithRedis.localCountersSize());

        // Phase 2: 让 rate bucket 过期（TTL 2min），daily bucket 未过期（TTL 2day）
        testClock.advance(Duration.ofMinutes(3));

        // Phase 3: Redis 恢复
        redisDown.set(false);

        // Phase 4: check() 走 Redis 路径，同时触发 maybeCleanupExpired 清理过期本地计数器
        limiterWithRedis.check(99L, 5);

        // 65 个过期的 rate bucket 被清理，65 个未过期的 daily bucket 保留，无新本地计数器
        assertEquals(65, limiterWithRedis.localCountersSize(),
                "Redis 恢复后 check() 必须清理过期本地计数器，且不再创建新的本地计数器");
    }

    // ============ Phase 3.2: 原子 Redis 脚本（UNIT CONTRACT TEST，非真实 Redis 集成） ============

    @SuppressWarnings("unchecked")
    private ObjectProvider<StringRedisTemplate> redisProviderReturning(StringRedisTemplate redisTemplate) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate redisMock() {
        return mock(StringRedisTemplate.class);
    }

    /** TEST 1 — Redis Script Path：Redis 可用时，check() 必须经由脚本 execute 完成，且不进入本地 fallback。 */
    @Test
    void redisScriptPathUsedWhenRedisAvailable() {
        StringRedisTemplate redisTemplate = redisMock();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
        VirtualTeacherUsageLimiter limiterWithRedis =
                new VirtualTeacherUsageLimiter(redisProviderReturning(redisTemplate), properties, clock);

        limiterWithRedis.check(42L, 5);

        // check() 触发两次脚本执行：rate 与 daily chars
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), anyList(), any(), any());
        assertEquals(0, limiterWithRedis.localCountersSize(), "Redis 可用时不得创建本地计数器");
    }

    /** TEST 2 — amount > 1：daily chars 必须以 textLength 作为 INCRBY amount，而非固定 1。 */
    @Test
    void scriptReceivesTextLengthAsAmountForDailyChars() {
        List<Object[]> calls = new ArrayList<>();
        StringRedisTemplate redisTemplate = redisMock();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenAnswer(inv -> {
            calls.add(inv.getArguments());
            return 1L;
        });
        VirtualTeacherUsageLimiter limiterWithRedis =
                new VirtualTeacherUsageLimiter(redisProviderReturning(redisTemplate), properties, clock);

        limiterWithRedis.check(42L, 123);

        // 两次 execute：rate（amount=1）与 daily chars（amount=123）
        assertEquals(2, calls.size());
        Object[] rateArgs = calls.get(0);
        Object[] charsArgs = calls.get(1);
        assertEquals("1", rateArgs[2], "rate 递增 amount 必须为 1");
        assertEquals("123", charsArgs[2], "daily chars 递增 amount 必须为 textLength=123，而非固定 1");
    }

    /** TEST 3 — TTL 参数：rate 传 Duration.ofMinutes(2)，daily chars 传 Duration.ofDays(2)，且以毫秒精度传递。 */
    @Test
    void scriptReceivesTtlInMillisecondsForRateAndDailyChars() {
        List<Object[]> calls = new ArrayList<>();
        StringRedisTemplate redisTemplate = redisMock();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenAnswer(inv -> {
            calls.add(inv.getArguments());
            return 1L;
        });
        VirtualTeacherUsageLimiter limiterWithRedis =
                new VirtualTeacherUsageLimiter(redisProviderReturning(redisTemplate), properties, clock);

        limiterWithRedis.check(42L, 5);

        Object[] rateArgs = calls.get(0);
        Object[] charsArgs = calls.get(1);
        assertEquals(String.valueOf(Duration.ofMinutes(2).toMillis()), rateArgs[3],
                "rate TTL 必须等于 Duration.ofMinutes(2) 的毫秒值");
        assertEquals(String.valueOf(Duration.ofDays(2).toMillis()), charsArgs[3],
                "daily chars TTL 必须等于 Duration.ofDays(2) 的毫秒值");
    }

    /** TEST 4 — 仅首次创建时设置 TTL 的脚本契约（UNIT CONTRACT TEST，不依赖真实 Redis）。 */
    @Test
    void scriptContractSetsTtlOnlyOnFirstCreation() {
        AtomicReference<RedisScript<Long>> capturedScript = new AtomicReference<>();
        StringRedisTemplate redisTemplate = redisMock();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenAnswer(inv -> {
            capturedScript.set(inv.getArgument(0));
            return 1L;
        });
        VirtualTeacherUsageLimiter limiterWithRedis =
                new VirtualTeacherUsageLimiter(redisProviderReturning(redisTemplate), properties, clock);

        limiterWithRedis.check(42L, 5);

        RedisScript<Long> script = capturedScript.get();
        assertNotNull(script, "check() 必须通过 RedisScript execute 完成限流递增");
        String lua = script.getScriptAsString();
        // 必须使用 INCRBY（而非 INCR），保持任意 amount 语义
        assertTrue(lua.contains("INCRBY"), "脚本必须使用 INCRBY 保持 amount 语义");
        // 必须使用 PEXPIRE（毫秒精度）
        assertTrue(lua.contains("PEXPIRE"), "脚本必须使用 PEXPIRE 设置 TTL");
        // key 经 KEYS[1]、amount/ttl 经 ARGV 传入
        assertTrue(lua.contains("KEYS[1]"), "key 必须通过 KEYS[1] 传给脚本");
        assertTrue(lua.contains("ARGV[1]"), "amount 必须通过 ARGV[1] 传入");
        assertTrue(lua.contains("ARGV[2]"), "ttl 必须通过 ARGV[2] 传入");
        // 仅 current == amount（首次创建）时设置 TTL：PEXPIRE 必须位于条件分支内
        int ifIdx = lua.indexOf("if current == tonumber(ARGV[1])");
        int pexpireIdx = lua.indexOf("PEXPIRE");
        int endIdx = lua.indexOf("end");
        assertTrue(ifIdx >= 0 && ifIdx < pexpireIdx && pexpireIdx < endIdx,
                "PEXPIRE 必须位于 'current == amount' 条件分支内，后续递增不得刷新 TTL");
    }

    /** TEST 5 — 脚本失败 fallback：redis.execute 抛 RuntimeException 时，本地 fallback 必须接管。 */
    @Test
    void scriptFailureFallsBackToLocalCounting() {
        properties.getRateLimit().setRequestsPerMinute(2);
        properties.getRateLimit().setDailyCharacters(100);
        StringRedisTemplate redisTemplate = redisMock();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RuntimeException("Redis 连接失败"));
        VirtualTeacherUsageLimiter limiterWithRedis =
                new VirtualTeacherUsageLimiter(redisProviderReturning(redisTemplate), properties, clock);

        limiterWithRedis.check(42L, 5); // 第 1 次：脚本失败 → 本地
        limiterWithRedis.check(42L, 5); // 第 2 次：脚本失败 → 本地，等于 limit=2

        assertThrows(TooManyRequestsException.class, () -> limiterWithRedis.check(42L, 5)); // 第 3 次超限

        assertEquals(2, limiterWithRedis.localCountersSize(),
                "脚本失败时必须由本地 fallback 承担计数（rate + daily chars）");
    }

    /** TEST 6 — existing limits regression：Redis 可用且脚本正常返回时，现有限流语义不变。 */
    @Test
    void redisScriptPathPreservesExistingLimitSemantics() {
        properties.getRateLimit().setRequestsPerMinute(2);
        properties.getRateLimit().setDailyCharacters(100);
        Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
        StringRedisTemplate redisTemplate = redisMock();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenAnswer(inv -> {
            String key = ((List<String>) inv.getArgument(1)).get(0);
            long current = counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
            return current;
        });
        VirtualTeacherUsageLimiter limiterWithRedis =
                new VirtualTeacherUsageLimiter(redisProviderReturning(redisTemplate), properties, clock);

        limiterWithRedis.check(42L, 5); // rate=1, chars=5
        limiterWithRedis.check(42L, 5); // rate=2, chars=10

        assertThrows(TooManyRequestsException.class, () -> limiterWithRedis.check(42L, 5)); // rate=3 > 2 超限

        assertEquals(0, limiterWithRedis.localCountersSize(), "Redis 正常时不得使用本地 fallback");
    }
}
