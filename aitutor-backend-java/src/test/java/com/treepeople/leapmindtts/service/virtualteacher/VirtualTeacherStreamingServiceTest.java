package com.treepeople.leapmindtts.service.virtualteacher;

import com.treepeople.leapmindtts.config.VirtualTeacherProperties;
import com.treepeople.leapmindtts.pojo.dto.VirtualTeacherTtsRequest;
import com.treepeople.leapmindtts.service.lesson.TextToSpeechService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式编排测试（B1/B3/B4/B6/B7/B8/B9/B10/B15/B16）。
 * 通过 mock StreamingTask 驱动 provider 语义，不调用真实阿里云。
 */
@ExtendWith(MockitoExtension.class)
class VirtualTeacherStreamingServiceTest {

    @Mock
    private TextToSpeechService textToSpeechService;
    @Mock
    private VirtualTeacherStreamingTtsClient streamingTtsClient;
    @Mock
    private VirtualTeacherTtsCache cache;
    @Mock
    private AudioStorageService storage;
    @Mock
    private VirtualTeacherUsageLimiter usageLimiter;
    @Mock
    private VirtualTeacherAuditService auditService;
    @Mock
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    private VirtualTeacherTtsService service;
    private VirtualTeacherProperties properties;
    private VirtualTeacherTtsRequest request;

    @BeforeEach
    void setUp() {
        properties = new VirtualTeacherProperties();
        service = new VirtualTeacherTtsService(
                textToSpeechService, streamingTtsClient, cache, storage,
                properties, usageLimiter, auditService, meterRegistryProvider);
        request = new VirtualTeacherTtsRequest();
        request.setText("同学们好，今天我们学习函数极限。");
        request.setVoiceType("zhixiaoxia");
        request.setSpeed(1.0);
    }

    // ---------- B7: cache hit skips provider ----------

    @Test
    void cacheHitSkipsProviderAndStreamsPcm() throws Exception {
        byte[] pcm = {1, 2, 3, 4};
        byte[] wav = PcmWavEncoder.encodeWav(pcm, 16000, (short) 1, (short) 16);
        when(cache.get(anyString())).thenReturn(Optional.of("cached.wav"));
        when(storage.load("cached.wav")).thenReturn(Optional.of(wav));

        VirtualTeacherTtsService.StreamingPlan plan = service.stream(42L, request);
        assertTrue(plan.cacheHit());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeStream(plan, out, 42L, request);

        assertArrayEquals(pcm, out.toByteArray());
        verify(streamingTtsClient, never()).stream(anyString(), anyString(), anyDouble());
        verify(auditService).recordStreamingTts(eq(42L), eq(request), eq(true), eq((long) pcm.length), anyLong());
    }

    // ---------- B8: cache miss stores valid WAV ----------

    @Test
    void cacheMissStreamsPcmAndStoresWav() throws Exception {
        byte[] chunkA = {1, 2};
        byte[] chunkB = {3, 4, 5, 6};
        when(cache.get(anyString())).thenReturn(Optional.empty());

        VirtualTeacherStreamingTtsClient.StreamingTask task = mockTask();
        when(streamingTtsClient.stream(anyString(), anyString(), anyDouble())).thenReturn(task);
        // poll: chunkA → chunkB → null
        when(task.poll(anyLong())).thenReturn(chunkA, chunkB, null);
        when(task.isCompleted()).thenReturn(false, false, true);
        when(task.isOverflowed()).thenReturn(false);
        when(task.isFailed()).thenReturn(false);

        VirtualTeacherTtsService.StreamingPlan plan = service.stream(42L, request);
        assertFalse(plan.cacheHit());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeStream(plan, out, 42L, request);

        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, out.toByteArray());
        // 存储的是合法 WAV（PCM 已被封装）
        verify(storage).store(anyString(), any(byte[].class), eq("audio/wav"));
        verify(cache).put(anyString(), anyString());
        verify(auditService).recordStreamingTts(eq(42L), eq(request), eq(false), eq(6L), anyLong());
    }

    // ---------- B3: pre-start provider failure ----------

    @Test
    void providerStartFailureThrowsBeforeCommit() throws Exception {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(streamingTtsClient.stream(anyString(), anyString(), anyDouble()))
                .thenThrow(new IllegalStateException("provider down"));

        assertThrows(IllegalStateException.class, () -> service.stream(42L, request));
        // 不触发任何存储/缓存
        verify(storage, never()).store(anyString(), any(), anyString());
        verify(cache, never()).put(anyString(), anyString());
    }

    // ---------- B4: mid-stream provider failure aborts without caching ----------

    @Test
    void midStreamFailureAbortsWithoutCaching() throws Exception {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        VirtualTeacherStreamingTtsClient.StreamingTask task = mockTask();
        when(streamingTtsClient.stream(anyString(), anyString(), anyDouble())).thenReturn(task);
        when(task.poll(anyLong())).thenReturn(new byte[]{1, 2}, null);
        when(task.isOverflowed()).thenReturn(false);
        when(task.isFailed()).thenReturn(false, false, true); // 首块后失败
        when(task.failureReason()).thenReturn(Optional.of("provider error"));

        VirtualTeacherTtsService.StreamingPlan plan = service.stream(42L, request);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> service.writeStream(plan, out, 42L, request));

        verify(task).cancel();
        verify(storage, never()).store(anyString(), any(), anyString());
        verify(cache, never()).put(anyString(), anyString());
        verify(auditService).recordTtsFailure(eq(42L), eq(request), any(), anyLong());
    }

    // ---------- B9: partial failed stream never caches ----------

    @Test
    void partialStreamNeverCachesOnFailure() throws Exception {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        VirtualTeacherStreamingTtsClient.StreamingTask task = mockTask();
        when(streamingTtsClient.stream(anyString(), anyString(), anyDouble())).thenReturn(task);
        when(task.poll(anyLong())).thenReturn(new byte[]{9, 9}, null);
        when(task.isOverflowed()).thenReturn(false);
        when(task.isFailed()).thenReturn(false, false, true);
        when(task.failureReason()).thenReturn(Optional.of("mid failure"));

        VirtualTeacherTtsService.StreamingPlan plan = service.stream(42L, request);
        assertThrows(IOException.class, () -> service.writeStream(plan, new ByteArrayOutputStream(), 42L, request));

        verify(storage, never()).store(anyString(), any(), anyString());
        verify(cache, never()).put(anyString(), anyString());
    }

    // ---------- B10: PCM safety budget aborts ----------

    @Test
    void pcmBudgetExceededAbortsWithoutCaching() throws Exception {
        properties.getStreaming().setMaxPcmBytes(4L); // 极小预算
        when(cache.get(anyString())).thenReturn(Optional.empty());
        VirtualTeacherStreamingTtsClient.StreamingTask task = mockTask();
        when(streamingTtsClient.stream(anyString(), anyString(), anyDouble())).thenReturn(task);
        when(task.poll(anyLong())).thenReturn(new byte[]{1, 2, 3, 4, 5}, null); // 5 > 4
        when(task.isOverflowed()).thenReturn(false);
        when(task.isFailed()).thenReturn(false);

        VirtualTeacherTtsService.StreamingPlan plan = service.stream(42L, request);
        assertThrows(IOException.class, () -> service.writeStream(plan, new ByteArrayOutputStream(), 42L, request));

        verify(task).cancel();
        verify(storage, never()).store(anyString(), any(), anyString());
        verify(cache, never()).put(anyString(), anyString());
    }

    // ---------- B6: client cancellation stops task ----------

    @Test
    void outputIOExceptionCancelsTask() throws Exception {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        VirtualTeacherStreamingTtsClient.StreamingTask task = mockTask();
        when(streamingTtsClient.stream(anyString(), anyString(), anyDouble())).thenReturn(task);
        when(task.poll(anyLong())).thenReturn(new byte[]{1, 2});
        when(task.isOverflowed()).thenReturn(false);
        when(task.isFailed()).thenReturn(false);

        // 客户端断开：write 抛 IOException
        java.io.OutputStream failing = new java.io.OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("client disconnected");
            }
        };
        VirtualTeacherTtsService.StreamingPlan plan = service.stream(42L, request);
        assertThrows(IOException.class, () -> service.writeStream(plan, failing, 42L, request));
        verify(task).cancel();
        verify(storage, never()).store(anyString(), any(), anyString());
    }

    // ---------- B15: limiter called exactly once ----------

    @Test
    void limiterCalledExactlyOnceOnStream() throws Exception {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        VirtualTeacherStreamingTtsClient.StreamingTask task = mockTask();
        when(streamingTtsClient.stream(anyString(), anyString(), anyDouble())).thenReturn(task);
        when(task.poll(anyLong())).thenReturn(new byte[]{1, 2}, null);
        when(task.isOverflowed()).thenReturn(false);
        when(task.isFailed()).thenReturn(false);
        when(task.isCompleted()).thenReturn(false, true);

        VirtualTeacherTtsService.StreamingPlan plan = service.stream(42L, request);
        service.writeStream(plan, new ByteArrayOutputStream(), 42L, request);

        verify(usageLimiter).check(42L, request.getText().trim().length());
        verify(streamingTtsClient).stream(anyString(), anyString(), anyDouble());
    }

    // ---------- B16: blocking /tts unchanged ----------

    @Test
    void blockingSynthesizeRemainsUnchanged() throws Exception {
        byte[] audio = {7, 8, 9};
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(textToSpeechService.synthesizeSpeech("同学们好，今天我们学习函数极限。", "zhixiaoxia", 1.0))
                .thenReturn(reactor.core.publisher.Mono.just(audio));
        when(storage.createReadUrl(anyString())).thenReturn("/audio/new.wav");

        VirtualTeacherTtsService.SynthesisResult result = service.synthesize(42L, request);
        assertFalse(result.response().isCacheHit());
        assertArrayEquals(audio, result.audio());
        verify(storage).store(anyString(), eq(audio), eq("audio/wav"));
        verify(cache).put(anyString(), anyString());
        verify(streamingTtsClient, never()).stream(anyString(), anyString(), anyDouble());
    }

    private VirtualTeacherStreamingTtsClient.StreamingTask mockTask() {
        return mock(VirtualTeacherStreamingTtsClient.StreamingTask.class);
    }
}
