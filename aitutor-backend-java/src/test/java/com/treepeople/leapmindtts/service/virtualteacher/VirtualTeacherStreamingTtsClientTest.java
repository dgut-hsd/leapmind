package com.treepeople.leapmindtts.service.virtualteacher;

import com.alibaba.nls.client.protocol.NlsClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式客户端单元测试：文本分段、音色/语速映射、有界队列与溢出语义。
 * <p>测试仅覆盖不依赖真实 provider 的逻辑（B2/B5 的队列语义、B1 的分块顺序）。
 */
class VirtualTeacherStreamingTtsClientTest {

    private final VirtualTeacherStreamingTtsClient client = new VirtualTeacherStreamingTtsClient(null, null);

    @Test
    void voiceAliasesPreserveExistingSemantics() {
        assertEquals("zhixiaoxia", client.resolveVoice("young-female-warm"));
        assertEquals("zhixiaobai", client.resolveVoice("young-female-clear"));
        assertEquals("zhixiaoxia", client.resolveVoice("young-female-natural"));
        assertEquals("zhixiaoxia", client.resolveVoice("default"));
        assertEquals("zhixiaoxia", client.resolveVoice(null));
        // raw voice passes through
        assertEquals("custom-voice", client.resolveVoice("custom-voice"));
    }

    @Test
    void speechRateMappingMatchesBlockingPath() {
        assertEquals(0, client.toSpeechRate(1.0));
        assertEquals(250, client.toSpeechRate(1.5));
        assertEquals(-250, client.toSpeechRate(0.5));
        assertEquals(500, client.toSpeechRate(2.0));  // clamped
        assertEquals(-500, client.toSpeechRate(0.0)); // clamped
    }

    @Test
    void shortTextIsSingleSegment() {
        List<String> segments = VirtualTeacherStreamingTtsClient.splitText("同学们好，今天我们学习极限。", 290);
        assertEquals(1, segments.size());
        assertEquals("同学们好，今天我们学习极限。", segments.get(0));
    }

    @Test
    void longTextSplitsAtSentenceBoundaryPreservingCharacters() {
        // 构造超过 290 字符、在句号处有清晰边界的文本
        StringBuilder sb = new StringBuilder();
        String sentence = "极限是微积分中最基础的概念，它描述了函数在某一点附近的变化趋势。";
        while (sb.length() < 300) {
            sb.append(sentence);
        }
        String text = sb.toString();
        List<String> segments = VirtualTeacherStreamingTtsClient.splitText(text, 290);

        assertTrue(segments.size() >= 2);
        // 所有段拼接后与原文一致（不丢失、不重复、不截断字符）
        assertEquals(text, String.join("", segments));
        // 句号边界优先：每段（除最后一段外）应以句号结尾
        for (int i = 0; i < segments.size() - 1; i++) {
            assertTrue(segments.get(i).endsWith("。"),
                    "segment " + i + " 应以句号结尾: " + segments.get(i).substring(Math.max(0, segments.get(i).length() - 5)));
        }
    }

    @Test
    void hardSplitWhenNoBoundaryExists() {
        // 无标点的 600 字符长文本 → 强制按 290 切分，字符无丢失
        String text = "a".repeat(600);
        List<String> segments = VirtualTeacherStreamingTtsClient.splitText(text, 290);
        assertEquals(3, segments.size());
        assertEquals(text, String.join("", segments));
    }

    @Test
    void emptyTextYieldsNoSegments() {
        assertTrue(VirtualTeacherStreamingTtsClient.splitText("", 290).isEmpty());
        assertTrue(VirtualTeacherStreamingTtsClient.splitText(null, 290).isEmpty());
    }

    // ---------- B2 / B5: bounded queue + overflow ----------

    private VirtualTeacherStreamingTtsClient.StreamingTask newQueueTask(int queueCapacity) {
        NlsClient clientMock = new NlsClient("wss://unused.invalid/ws/v1", "test-token");
        ThreadPoolExecutor driver = new ThreadPoolExecutor(
                1, 2, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8),
                r -> {
                    Thread t = new Thread(r, "m8-stream-driver-test");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        return new VirtualTeacherStreamingTtsClient.StreamingTask(
                clientMock, List.of("你好"), "zhixiaoxia", 0, "appkey",
                queueCapacity, 30_000, driver);
    }

    @Test
    void queuePreservesChunkOrderUntilFull() throws InterruptedException {
        VirtualTeacherStreamingTtsClient.StreamingTask task = newQueueTask(2);

        task.onChunk(new byte[]{1, 2});
        task.onChunk(new byte[]{3, 4, 5});

        assertEquals(2, task.queueSize());
        byte[] first = task.poll(100);
        byte[] second = task.poll(100);
        assertEquals(1, first[0]);
        assertEquals(2, first[1]);
        assertEquals(3, second[0]);
        assertEquals(5, second[2]);
    }

    @Test
    void queueOverflowMarksFlagWithoutBlocking() {
        VirtualTeacherStreamingTtsClient.StreamingTask task = newQueueTask(1);

        task.onChunk(new byte[]{1});
        assertFalse(task.isOverflowed());
        // 第二次 offer 应失败并标记 overflow（非阻塞）
        task.onChunk(new byte[]{2});
        assertTrue(task.isOverflowed());
    }
}
