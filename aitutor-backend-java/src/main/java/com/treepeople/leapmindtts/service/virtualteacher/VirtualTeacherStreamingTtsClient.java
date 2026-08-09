package com.treepeople.leapmindtts.service.virtualteacher;

import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.OutputFormatEnum;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizer;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerListener;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerResponse;
import com.treepeople.leapmindtts.config.VirtualTeacherProperties;
import com.treepeople.leapmindtts.service.lesson.AliyunTokenService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * M8-owned streaming TTS client.
 *
 * <p>Wraps the Alibaba NLS classic {@link SpeechSynthesizer} (incremental
 * {@code onMessage(ByteBuffer)} PCM callbacks) behind a bounded, non-blocking
 * callback contract for M8 true streaming. The client owns ONLY provider
 * lifecycle concerns: global {@link NlsClient}, token refresh, per-task
 * synthesizer creation, text splitting, PCM chunk forwarding, cancellation and
 * terminal events. HTTP, storage and cache logic live outside this class.
 *
 * <p>Callback contract (Netty threads): each {@code onMessage} copies the chunk
 * and {@code offer}s it into a bounded queue, returning immediately. A full
 * queue marks an atomic overflow flag and schedules cancellation outside the
 * callback. No blocking SDK operation is ever invoked from a provider callback.
 */
@Slf4j
@Component
public class VirtualTeacherStreamingTtsClient {

    /** 合成器创建工厂（测试注入 fake，生产环境创建真实 SpeechSynthesizer）。 */
    @FunctionalInterface
    interface SpeechSynthesizerFactory {
        SpeechSynthesizer create(NlsClient client, SpeechSynthesizerListener listener) throws Exception;
    }

    /** Provider voice alias mapping — mirrors existing M8 blocking semantics. */
    static final Map<String, String> VOICE_ALIASES = Map.of(
            "young-female-warm", "zhixiaoxia",
            "young-female-clear", "zhixiaobai",
            "young-female-natural", "zhixiaoxia"
    );

    /** Alibaba NLS default service URL (Shanghai). */
    private static final String NLS_URL = "wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1";

    private final AliyunTokenService tokenService;
    private final VirtualTeacherProperties properties;
    private final SpeechSynthesizerFactory synthesizerFactory;

    @Value("${tts.app.key:}")
    private String appKey;

    /** Application-global, thread-safe, reusable client. */
    private volatile NlsClient nlsClient;

    /**
     * 应用生命周期内的有界执行器：驱动 provider 分段顺序合成。
     *
     * <p>使用 {@link SynchronousQueue} 实现 immediate-handoff 准入：
     * <ul>
     *   <li>无普通工作队列（不存在 driver Runnable 排队等待的情况）；</li>
     *   <li>存在空闲 worker → 任务立即接手执行（body-start 看门狗从 provider 启动即开始）；</li>
     *   <li>所有 worker 被占用 → execute() 立即抛 {@link RejectedExecutionException}，
     *       调用方必须关闭已启动的 SpeechSynthesizer 并让请求失败；</li>
     *   <li>core == max == N（有限），AbortPolicy fail-fast，不用缓存线程池/CallerRuns。</li>
     * </ul>
     */
    private static final int PROVIDER_DRIVER_WORKERS = 2;
    private final ThreadPoolExecutor providerDriverExecutor;

    public VirtualTeacherStreamingTtsClient(
            AliyunTokenService tokenService,
            VirtualTeacherProperties properties) {
        this(tokenService, properties, SpeechSynthesizer::new);
    }

    VirtualTeacherStreamingTtsClient(
            AliyunTokenService tokenService,
            VirtualTeacherProperties properties,
            SpeechSynthesizerFactory synthesizerFactory) {
        this.tokenService = tokenService;
        this.properties = properties;
        this.synthesizerFactory = synthesizerFactory;
        this.providerDriverExecutor = new ThreadPoolExecutor(
                PROVIDER_DRIVER_WORKERS,             // core
                PROVIDER_DRIVER_WORKERS,             // max（固定有界，有限 N）
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),            // 无普通队列：立即交接或立即拒绝
                runnable -> {
                    Thread thread = new Thread(runnable, "m8-stream-driver");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy() // fail-fast，拒绝时抛 RejectedExecutionException
        );
        this.providerDriverExecutor.prestartAllCoreThreads();
    }

    /**
     * Begin a streaming synthesis task.
     *
     * <p>Pre-commit semantics: the first segment's synthesizer is created and
     * {@code start()}ed synchronously. If provider startup fails here, the
     * caller may still return a normal HTTP error before the response commits.
     *
     * @param text   full narration text (request-validated, ≤ 500 chars)
     * @param voice  M8 voice alias or raw voice name
     * @param speed  speaking speed (0.5–2.0, mapped like the blocking path)
     * @return streaming task handle
     * @throws Exception if provider startup fails synchronously
     */
    public StreamingTask stream(String text, String voice, double speed) throws Exception {
        String resolvedVoice = resolveVoice(voice);
        int speechRate = toSpeechRate(speed);
        List<String> segments = splitText(text, properties.getStreaming().getSegmentMaxChars());
        NlsClient client = ensureClient();

        return new StreamingTask(client, segments, resolvedVoice, speechRate,
                appKey, properties.getStreaming().getQueueCapacity(),
                properties.getSynthesisTimeout().toMillis(),
                providerDriverExecutor, synthesizerFactory);
    }

    /**
     * Returns the application-global {@link NlsClient}, refreshing the token on
     * the existing instance (no recreation) before expiry.
     */
    private NlsClient ensureClient() throws Exception {
        NlsClient current = nlsClient;
        if (current == null) {
            synchronized (this) {
                if (nlsClient == null) {
                    AliyunTokenService.TokenWithExpiry tokenWithExpiry =
                            tokenService.getTokenWithExpiry().block(Duration.ofSeconds(30));
                    if (tokenWithExpiry == null || tokenWithExpiry.token().isBlank()) {
                        throw new IllegalStateException("获取阿里云 NLS Token 失败");
                    }
                    NlsClient created = new NlsClient(NLS_URL, tokenWithExpiry.token());
                    nlsClient = created;
                    log.info("已创建全局 NlsClient");
                    return created;
                }
            }
            current = nlsClient;
        }
        // Token refresh: update on the SAME client instance (SDK 2.2.14 provides setToken).
        try {
            AliyunTokenService.TokenWithExpiry tokenWithExpiry =
                    tokenService.getTokenWithExpiry().block(Duration.ofSeconds(30));
            if (tokenWithExpiry != null && !tokenWithExpiry.token().isBlank()
                    && tokenExpiringSoon(tokenWithExpiry)) {
                current.setToken(tokenWithExpiry.token());
                log.info("已刷新全局 NlsClient Token");
            }
        } catch (Exception error) {
            log.warn("NlsClient Token 刷新失败（沿用现有 Token）: {}", error.getMessage());
        }
        return current;
    }

    private boolean tokenExpiringSoon(AliyunTokenService.TokenWithExpiry tokenWithExpiry) {
        long now = System.currentTimeMillis() / 1000;
        return tokenWithExpiry.expireEpochSeconds() - now < 10 * 60; // refresh within 10 min of expiry
    }

    String resolveVoice(String voice) {
        if (voice == null || voice.isBlank() || "default".equalsIgnoreCase(voice)) {
            return "zhixiaoxia";
        }
        return VOICE_ALIASES.getOrDefault(voice, voice);
    }

    /** Mirror of the blocking path: (speed - 1.0) * 500, clamped to SDK range. */
    int toSpeechRate(double speed) {
        double rate = (speed - 1.0) * 500;
        return (int) Math.max(-500, Math.min(500, rate));
    }

    /**
     * Split text into ≤ {@code segmentMaxChars} segments, preferring sentence
     * boundaries, preserving every source character, hard-splitting only when no
     * boundary exists. For the 500-char M8 contract this yields at most 2 segments.
     */
    static List<String> splitText(String text, int segmentMaxChars) {
        List<String> segments = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return segments;
        }
        int limit = Math.max(1, segmentMaxChars);
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + limit, text.length());
            if (end < text.length()) {
                int boundary = lastSentenceBoundary(text, start, end);
                if (boundary > start + limit / 2) {
                    end = boundary;
                }
            }
            segments.add(text.substring(start, end));
            start = end;
        }
        return segments;
    }

    private static int lastSentenceBoundary(String text, int start, int end) {
        int best = -1;
        String sub = text.substring(start, end);
        String[] markers = {"。", "！", "？", "；", ";", ".", "!", "?", "\n"};
        for (String marker : markers) {
            int idx = sub.lastIndexOf(marker);
            if (idx > best) {
                best = idx;
            }
        }
        return best >= 0 ? start + best + 1 : -1;
    }

    @PreDestroy
    public void shutdown() {
        NlsClient current = nlsClient;
        if (current != null) {
            try {
                current.shutdown();
                log.info("全局 NlsClient 已关闭");
            } catch (Exception error) {
                log.warn("关闭全局 NlsClient 失败: {}", error.getMessage());
            }
        }
        providerDriverExecutor.shutdownNow();
        log.info("provider 驱动线程池已关闭");
    }

    /**
     * A single streaming synthesis task. Per-task {@link SpeechSynthesizer} +
     * {@link SpeechSynthesizerListener} instances are never shared between tasks.
     *
     * <p>Threading: provider callbacks (Netty threads) only copy chunks, offer to
     * the bounded queue and signal completion — never blocking SDK calls. Segment
     * sequencing runs on the shared application-lifecycle bounded executor
     * ({@link VirtualTeacherStreamingTtsClient#providerDriverExecutor}) so
     * synthesizer creation/start/close never execute on a callback thread and
     * never spawn per-request raw Java threads.
     */
    public static class StreamingTask {

        private final NlsClient client;
        private final List<String> segments;
        private final String voice;
        private final int speechRate;
        private final String appKey;
        private final long segmentTimeoutMs;
        private final ThreadPoolExecutor driverExecutor;
        private final SpeechSynthesizerFactory synthesizerFactory;
        private final BlockingQueue<byte[]> queue;
        private final AtomicBoolean overflow = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean bodyStarted = new AtomicBoolean(false);
        private final AtomicReference<String> failure = new AtomicReference<>(null);
        private final AtomicBoolean completed = new AtomicBoolean(false);

        /** MVC 异步响应体开始执行的最长等待（内存/资源安全限制，非 SLA）。 */
        private static final long DEFAULT_BODY_START_GRACE_MS = 5000L;
        private final long bodyStartGraceMs;

        private final Object segmentMonitor = new Object();
        private volatile int completedSegmentIndex = -1;
        private volatile SpeechSynthesizer currentSynthesizer;

        /** body-start 看门狗期限（单调时钟，锚定 provider 准入 T0）。 */
        private volatile long bodyStartDeadlineNanos = 0L;

        public StreamingTask(NlsClient client, List<String> segments, String voice,
                             int speechRate, String appKey, int queueCapacity, long segmentTimeoutMs,
                             ThreadPoolExecutor driverExecutor) {
            this(client, segments, voice, speechRate, appKey, queueCapacity, segmentTimeoutMs,
                    driverExecutor, SpeechSynthesizer::new, DEFAULT_BODY_START_GRACE_MS);
        }

        StreamingTask(NlsClient client, List<String> segments, String voice,
                      int speechRate, String appKey, int queueCapacity, long segmentTimeoutMs,
                      ThreadPoolExecutor driverExecutor, SpeechSynthesizerFactory synthesizerFactory) {
            this(client, segments, voice, speechRate, appKey, queueCapacity, segmentTimeoutMs,
                    driverExecutor, synthesizerFactory, DEFAULT_BODY_START_GRACE_MS);
        }

        StreamingTask(NlsClient client, List<String> segments, String voice,
                      int speechRate, String appKey, int queueCapacity, long segmentTimeoutMs,
                      ThreadPoolExecutor driverExecutor, SpeechSynthesizerFactory synthesizerFactory,
                      long bodyStartGraceMs) {
            this.client = client;
            this.segments = segments;
            this.voice = voice;
            this.speechRate = speechRate;
            this.appKey = appKey;
            this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
            this.segmentTimeoutMs = Math.max(1, segmentTimeoutMs);
            this.driverExecutor = driverExecutor;
            this.synthesizerFactory = synthesizerFactory;
            this.bodyStartGraceMs = Math.max(1, bodyStartGraceMs);
        }

        /**
         * Start the first segment synchronously (pre-commit). Throws on provider
         * startup failure so the HTTP layer can return an error before committing.
         *
         * <p>Admission semantics: the body-start watchdog deadline is anchored to
         * PROVIDER ADMISSION (T0 = monotonic time captured here, BEFORE segment 0
         * starts), never to when a driver Runnable finally executes. The driver
         * executor uses a SynchronousQueue (no normal work queue), so execute()
         * either hands off to an idle worker immediately or rejects immediately;
         * if rejected, the already-started synthesizer is closed and the task
         * fails (no orphan provider work).
         */
        public void start() throws Exception {
            if (segments.isEmpty()) {
                completed.set(true);
                return;
            }
            // T0：body-start 看门狗期限从 provider 准入开始计算（单调时钟）
            bodyStartDeadlineNanos = System.nanoTime() + bodyStartGraceMs * 1_000_000L;
            startSegment(0);
            try {
                driverExecutor.execute(this::driveSegments);
            } catch (RejectedExecutionException error) {
                onProviderFailure("provider 驱动线程池已满，任务被拒绝", error);
                closeCurrentSynthesizer();
                throw new IllegalStateException("provider 驱动线程池已满，流式合成被拒绝", error);
            }
        }

        /**
         * Segment driver: waits for the MVC async body to start (or the grace
         * window to expire), then sequences remaining segments. Never runs on a
         * provider callback thread.
         *
         * <p>Orphan protection: if the StreamingResponseBody is rejected / never
         * executes (e.g. MVC async executor saturation), the driver cancels the
         * already-started synthesizer instead of leaving provider work running.
         */
        private void driveSegments() {
            try {
                if (!awaitBodyStart()) {
                    return;
                }
                for (int i = 1; i < segments.size(); i++) {
                    if (!awaitSegmentCompletion(i - 1)) {
                        return;
                    }
                    if (cancelled.get() || failure.get() != null) {
                        return;
                    }
                    closeCurrentSynthesizer();
                    if (cancelled.get() || failure.get() != null) {
                        return;
                    }
                    startSegment(i);
                }
            } catch (Exception error) {
                onProviderFailure("segment sequencing failed", error);
            }
        }

        private boolean awaitBodyStart() {
            long deadline = bodyStartDeadlineNanos;
            if (deadline <= 0) {
                deadline = System.nanoTime() + bodyStartGraceMs * 1_000_000L;
            }
            synchronized (segmentMonitor) {
                while (!bodyStarted.get() && !cancelled.get() && failure.get() == null) {
                    long remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0) {
                        onProviderFailure("流式响应体未在限期内开始执行（MVC 异步可能被拒绝），已取消 provider",
                                new IllegalStateException("streaming body did not start within grace window"));
                        closeCurrentSynthesizer();
                        return false;
                    }
                    try {
                        // 关键：wait(0) 表示永久等待。当剩余时间 < 1ms（截断为 0）时必须
                        // 至少等待 1ms，确保循环重新检查期限，看门狗不会被卡住。
                        long waitMs = Math.min(Math.max(remainingNanos / 1_000_000L, 1L), 250L);
                        segmentMonitor.wait(waitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return !cancelled.get() && failure.get() == null;
            }
        }

        /** 由 StreamingResponseBody 执行体在开始消费时调用（响应体已运行）。 */
        public void markBodyStarted() {
            bodyStarted.set(true);
            synchronized (segmentMonitor) {
                segmentMonitor.notifyAll();
            }
        }

        private boolean awaitSegmentCompletion(int segmentIndex) {
            long deadline = System.currentTimeMillis() + segmentTimeoutMs;
            synchronized (segmentMonitor) {
                while (completedSegmentIndex < segmentIndex
                        && !cancelled.get() && failure.get() == null) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        onProviderFailure("等待 segment " + segmentIndex + " 完成超时",
                                new IllegalStateException("synthesis segment timeout"));
                        return false;
                    }
                    try {
                        segmentMonitor.wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return !cancelled.get() && failure.get() == null;
            }
        }

        private void startSegment(int index) throws Exception {
            SpeechSynthesizerListener listener = new SpeechSynthesizerListener() {
                @Override
                public void onMessage(ByteBuffer message) {
                    byte[] copy = new byte[message.remaining()];
                    message.get(copy);
                    onChunk(copy);
                }

                @Override
                public void onMessage(String message) {
                    // text-mode message (not used with binary PCM)
                }

                @Override
                public void onComplete(SpeechSynthesizerResponse response) {
                    // Non-blocking: publish segment completion, wake the driver.
                    synchronized (segmentMonitor) {
                        completedSegmentIndex = index;
                        segmentMonitor.notifyAll();
                    }
                    if (index == segments.size() - 1) {
                        completed.set(true);
                        log.debug("流式合成完成: segment {} (last)", index);
                    }
                }

                @Override
                public void onFail(SpeechSynthesizerResponse response) {
                    onProviderFailure("provider onFail status=" + response.getStatus()
                            + " text=" + response.getStatusText(),
                            new IllegalStateException(response.getStatusText()));
                }
            };

            SpeechSynthesizer synthesizer = synthesizerFactory.create(client, listener);
            synthesizer.setAppKey(appKey);
            synthesizer.setFormat(OutputFormatEnum.PCM);
            synthesizer.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
            synthesizer.setVoice(voice);
            synthesizer.setSpeechRate(speechRate);
            synthesizer.setVolume(50);
            synthesizer.setText(segments.get(index));
            currentSynthesizer = synthesizer;
            synthesizer.start();
            log.info("流式合成 segment {} 已启动: textLength={}", index, segments.get(index).length());
        }

        private void closeCurrentSynthesizer() {
            SpeechSynthesizer synthesizer = currentSynthesizer;
            if (synthesizer != null) {
                try {
                    synthesizer.close();
                } catch (Exception error) {
                    log.warn("关闭 synthesizer 失败: {}", error.getMessage());
                }
            }
        }

        /**
         * Offer a PCM chunk into the bounded queue. Package-visible so tests can
         * exercise queue/overflow semantics directly; production callers are the
         * provider {@code onMessage} callbacks.
         */
        void onChunk(byte[] chunk) {
            if (overflow.get() || cancelled.get()) {
                return;
            }
            if (!queue.offer(chunk)) {
                // Non-blocking: mark overflow atomically; cancellation is scheduled
                // outside this callback by the draining side.
                if (overflow.compareAndSet(false, true)) {
                    log.warn("流式 PCM 队列已满，标记 overflow（等待消费端取消）");
                }
            }
        }

        private void onProviderFailure(String message, Throwable cause) {
            if (failure.compareAndSet(null, message)) {
                log.error("流式合成失败: {}", message, cause);
            }
            synchronized (segmentMonitor) {
                segmentMonitor.notifyAll();
            }
            completed.set(true);
        }

        /**
         * Blocking drain helper: returns the next PCM chunk, or {@code null} if
         * the queue is currently empty. Callers poll with a timeout to observe
         * terminal state.
         */
        public byte[] poll(long timeoutMs) throws InterruptedException {
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }

        public boolean isOverflowed() {
            return overflow.get();
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public boolean isCompleted() {
            return completed.get();
        }

        public boolean isFailed() {
            return failure.get() != null;
        }

        public Optional<String> failureReason() {
            return Optional.ofNullable(failure.get());
        }

        /**
         * Cancel the task from a NON-callback thread. Closes the current
         * synthesizer so the provider stops producing. Never called from a
         * provider callback (contract: callbacks only offer + set flags).
         */
        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            synchronized (segmentMonitor) {
                segmentMonitor.notifyAll();
            }
            closeCurrentSynthesizer();
            log.info("流式合成任务已取消");
        }

        /** Remaining queue size (for tests / diagnostics). */
        int queueSize() {
            return queue.size();
        }
    }
}
