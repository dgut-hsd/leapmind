package com.treepeople.leapmindtts.service.virtualteacher;

import com.treepeople.leapmindtts.config.VirtualTeacherProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.treepeople.leapmindtts.pojo.dto.VirtualTeacherTtsRequest;
import com.treepeople.leapmindtts.pojo.vo.VirtualTeacherTtsVO;
import com.treepeople.leapmindtts.service.lesson.TextToSpeechService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualTeacherTtsService {
    public static final String AUDIO_CONTENT_TYPE = "audio/wav";
    private final TextToSpeechService textToSpeechService;
    private final VirtualTeacherStreamingTtsClient streamingTtsClient;
    private final VirtualTeacherTtsCache cache;
    private final AudioStorageService storage;
    private final VirtualTeacherProperties properties;
    private final VirtualTeacherUsageLimiter usageLimiter;
    private final VirtualTeacherAuditService auditService;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public SynthesisResult synthesize(VirtualTeacherTtsRequest request) {
        return synthesize(null, request);
    }

    public SynthesisResult synthesize(Long userId, VirtualTeacherTtsRequest request) {
        long startedAt = System.nanoTime();
        int textLength = request.getText() == null ? 0 : request.getText().trim().length();
        try {
            if (userId != null) {
                usageLimiter.check(userId, textLength);
            }
            SynthesisResult result = synthesizeInternal(request);
            long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            auditService.recordTts(userId, request, result, latencyMs);
            recordMetrics(result, latencyMs, "success");
            return result;
        } catch (RuntimeException error) {
            long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            auditService.recordTtsFailure(userId, request, error, latencyMs);
            recordFailureMetric(error);
            throw error;
        }
    }

    private SynthesisResult synthesizeInternal(VirtualTeacherTtsRequest request) {
        String voice = request.getVoiceType() == null || request.getVoiceType().isBlank()
                ? "default"
                : request.getVoiceType().trim();
        double speed = request.getSpeed() == null ? 1.0 : request.getSpeed();
        String hash = sha256(request.getText().trim() + "\n" + voice + "\n" + speed);
        String cacheKey = "tts:audio:" + hash;

        Optional<String> cachedObjectKey = cache.get(cacheKey);
        if (cachedObjectKey.isPresent()) {
            Optional<byte[]> cachedAudio = storage.load(cachedObjectKey.get());
            if (cachedAudio.isPresent()) {
                return buildResult(cachedAudio.get(), cachedObjectKey.get(), cacheKey, true);
            }
        }

        byte[] audio = textToSpeechService
                .synthesizeSpeech(request.getText().trim(), voice, speed)
                .block(properties.getSynthesisTimeout());
        if (audio == null || audio.length == 0) {
            throw new IllegalStateException("TTS 服务返回空音频");
        }

        String objectKey = hash + ".wav";
        storage.store(objectKey, audio, AUDIO_CONTENT_TYPE);
        cache.put(cacheKey, objectKey);
        return buildResult(audio, objectKey, cacheKey, false);
    }

    private void recordMetrics(SynthesisResult result, long latencyMs, String status) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        if (meterRegistry == null) return;
        meterRegistry.counter(
                "virtual_teacher_tts_requests_total",
                "status", status,
                "cache", result.response().isCacheHit() ? "hit" : "miss").increment();
        meterRegistry.summary("virtual_teacher_tts_audio_bytes")
                .record(result.response().getAudioSize());
        Timer.builder("virtual_teacher_tts_latency")
                .tag("status", status)
                .register(meterRegistry)
                .record(Duration.ofMillis(latencyMs));
    }

    private void recordFailureMetric(RuntimeException error) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        if (meterRegistry == null) return;
        meterRegistry.counter(
                "virtual_teacher_tts_requests_total",
                "status", "failed",
                "error", error.getClass().getSimpleName()).increment();
    }

    public Optional<byte[]> loadAudio(String objectKey) {
        return storage.load(objectKey);
    }

    /**
     * 流式合成计划。区分缓存命中（直接写 PCM）与缓存未命中（驱动 provider 流）。
     */
    public record StreamingPlan(
            boolean cacheHit,
            byte[] cachedPcm,
            VirtualTeacherStreamingTtsClient.StreamingTask task,
            String objectKey,
            String cacheKey) {
    }

    /**
     * 流式合成预检（pre-commit）：限流、缓存查找、provider 启动。
     * <p>在响应提交前调用；provider 启动失败时抛出异常，由异常处理器返回
     * 正常 HTTP 错误（429/500），此时响应尚未提交。
     */
    public StreamingPlan stream(Long userId, VirtualTeacherTtsRequest request) {
        int textLength = request.getText() == null ? 0 : request.getText().trim().length();
        if (userId != null) {
            usageLimiter.check(userId, textLength);
        }
        String voice = request.getVoiceType() == null || request.getVoiceType().isBlank()
                ? "default"
                : request.getVoiceType().trim();
        double speed = request.getSpeed() == null ? 1.0 : request.getSpeed();
        String hash = sha256(request.getText().trim() + "\n" + voice + "\n" + speed);
        String cacheKey = "tts:audio:" + hash;
        String objectKey = hash + ".wav";

        Optional<String> cachedObjectKey = cache.get(cacheKey);
        if (cachedObjectKey.isPresent()) {
            Optional<byte[]> cachedAudio = storage.load(cachedObjectKey.get());
            if (cachedAudio.isPresent()) {
                try {
                    WavPcmParser.PcmPayload payload = WavPcmParser.parse(cachedAudio.get());
                    if (payload.matchesStreamingContract()) {
                        return new StreamingPlan(true, payload.pcm(), null, objectKey, cacheKey);
                    }
                    log.warn("缓存 WAV 与流式契约不兼容，回退到 provider 合成");
                } catch (IllegalArgumentException error) {
                    log.warn("缓存 WAV 解析失败，回退到 provider 合成: {}", error.getMessage());
                }
            }
        }

        try {
            VirtualTeacherStreamingTtsClient.StreamingTask task = streamingTtsClient
                    .stream(request.getText().trim(), voice, speed);
            task.start(); // 同步 provider 握手；失败则在此抛出让 HTTP 层返回 5xx
            return new StreamingPlan(false, null, task, objectKey, cacheKey);
        } catch (Exception error) {
            throw new IllegalStateException("流式 TTS provider 启动失败", error);
        }
    }

    /**
     * 将流式计划写入 HTTP 输出流（post-commit）。
     * <p>缓存命中：直接写缓存的 PCM。缓存未命中：从任务队列取块写客户端，
     * 同时追加到有界累积器；合成完成后封装为 WAV 并写入现有存储/缓存。
     * 中途失败（溢出/失败/预算超限）不缓存部分音频，抛 IOException 终止响应。
     */
    public void writeStream(StreamingPlan plan, OutputStream out, Long userId, VirtualTeacherTtsRequest request)
            throws IOException {
        long startedAt = System.nanoTime();
        // 响应体已开始执行：唤醒 provider 分段驱动（MVC 异步拒绝时不会到达此处，
        // 由 StreamingTask 的 body-start 宽限窗口取消 provider，避免孤儿任务）。
        if (plan.task() != null) {
            plan.task().markBodyStarted();
        }
        if (plan.cacheHit()) {
            try {
                out.write(plan.cachedPcm());
                out.flush();
                long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
                auditService.recordStreamingTts(userId, request, true,
                        plan.cachedPcm().length, latencyMs);
            } catch (IOException error) {
                throw error;
            }
            return;
        }

        VirtualTeacherStreamingTtsClient.StreamingTask task = plan.task();
        long maxPcmBytes = properties.getStreaming().getMaxPcmBytes();
        ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
        boolean stored = false;
        try {
            while (true) {
                if (task.isOverflowed()) {
                    throw new IOException("流式 PCM 队列溢出");
                }
                if (task.isFailed()) {
                    throw new IOException("流式合成失败: " + task.failureReason().orElse("未知错误"));
                }
                byte[] chunk = task.poll(100);
                if (chunk != null) {
                    if (accumulator.size() + chunk.length > maxPcmBytes) {
                        throw new IOException("PCM 累积超过安全预算，已中止");
                    }
                    accumulator.write(chunk);
                    out.write(chunk);
                    out.flush();
                } else if (task.isCompleted()) {
                    break;
                }
            }
            byte[] pcm = accumulator.toByteArray();
            if (pcm.length == 0) {
                throw new IOException("流式合成返回空音频");
            }
            byte[] wav = PcmWavEncoder.encodeWav(
                    pcm, WavPcmParser.SAMPLE_RATE_16K,
                    WavPcmParser.CHANNELS_MONO, WavPcmParser.BITS_PER_SAMPLE_16);
            storage.store(plan.objectKey(), wav, AUDIO_CONTENT_TYPE);
            cache.put(plan.cacheKey(), plan.objectKey());
            stored = true;
            long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            auditService.recordStreamingTts(userId, request, false, pcm.length, latencyMs);
        } catch (IOException error) {
            long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            auditService.recordTtsFailure(userId, request, error, latencyMs);
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            auditService.recordTtsFailure(userId, request, error, latencyMs);
            throw new IOException("流式合成被中断", error);
        } finally {
            // 仅在未成功存储时取消（幂等）：释放 provider，保证不缓存部分音频。
            if (!stored) {
                task.cancel();
            }
        }
    }

    private SynthesisResult buildResult(
            byte[] audio,
            String objectKey,
            String cacheKey,
            boolean cacheHit) {
        VirtualTeacherTtsVO response = VirtualTeacherTtsVO.builder()
                .audioUrl(storage.createReadUrl(objectKey))
                .contentType(AUDIO_CONTENT_TYPE)
                .audioSize((long) audio.length)
                .cacheHit(cacheHit)
                .cacheKey(cacheKey)
                .build();
        return new SynthesisResult(audio, response);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算 TTS 缓存键", e);
        }
    }

    public record SynthesisResult(byte[] audio, VirtualTeacherTtsVO response) {
    }
}
