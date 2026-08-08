package com.treepeople.leapmindtts.service.virtualteacher;

import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizer;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * P1–P3 + MVC 异步拒绝清理 + Q1–Q4 并发正确性测试。
 * 使用 fake SpeechSynthesizerFactory 注入 mock，不调用真实阿里云。
 */
class VirtualTeacherStreamingTaskLifecycleTest {

    private final List<ThreadPoolExecutor> executorsToShutdown = new ArrayList<>();
    private final List<VirtualTeacherStreamingTtsClient.StreamingTask> tasksToCancel = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (VirtualTeacherStreamingTtsClient.StreamingTask task : tasksToCancel) {
            try {
                task.cancel();
            } catch (Exception ignored) {
                // cleanup only
            }
        }
        for (ThreadPoolExecutor executor : executorsToShutdown) {
            executor.shutdownNow();
        }
    }

    private ThreadPoolExecutor driverExecutor() {
        // 与生产实现一致：SynchronousQueue immediate-handoff，core==max==2，AbortPolicy
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 2, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "m8-stream-driver-test");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.prestartAllCoreThreads();
        executorsToShutdown.add(executor);
        return executor;
    }

    private void occupyWorker(ThreadPoolExecutor executor, long holdMs) throws InterruptedException {
        // SynchronousQueue 立即交接存在启动竞态（prestarted worker 尚未进入 take()）：
        // 有界重试直到任务确实被某 worker 接收并开始执行。
        for (int attempt = 0; attempt < 200; attempt++) {
            java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
            try {
                executor.execute(() -> {
                    started.countDown();
                    try {
                        Thread.sleep(holdMs);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
                started.await(1, TimeUnit.SECONDS);
                return;
            } catch (RejectedExecutionException race) {
                // worker 尚未就绪（未进入 take()）：短暂让出后重试
                Thread.sleep(10);
            }
        }
        throw new IllegalStateException("无法占用 provider driver worker");
    }

    private SpeechSynthesizer mockSynthesizer() {
        return mock(SpeechSynthesizer.class);
    }

    private VirtualTeacherStreamingTtsClient.StreamingTask newTask(
            ThreadPoolExecutor executor,
            List<String> segments,
            long graceMs,
            SpeechSynthesizer fake) {
        return new VirtualTeacherStreamingTtsClient.StreamingTask(
                new NlsClient("wss://unused.invalid/ws/v1", "test-token"),
                segments, "zhixiaoxia", 0, "appkey", 8, 30_000,
                executor, (client, listener) -> fake, graceMs);
    }

    // ---------- P1: executor rejection leaves no live synthesis task ----------

    @Test
    void executorRejectionCancelsStartedSynthesizerAndFailsTask() throws Exception {
        ThreadPoolExecutor executor = driverExecutor();
        executor.shutdown(); // 立即拒绝后续提交（AbortPolicy）

        SpeechSynthesizer fake = mockSynthesizer();
        VirtualTeacherStreamingTtsClient.StreamingTask task =
                newTask(executor, List.of("第一段", "第二段"), 5000, fake);
        tasksToCancel.add(task);

        assertThrows(IllegalStateException.class, task::start);
        assertTrue(task.isFailed(), "任务应标记失败");
        assertTrue(task.failureReason().isPresent());
        // 已启动的 synthesizer 必须被关闭（无孤儿 provider 工作）
        verify(fake).close();
    }

    // ---------- P2: multiple sessions do not create one raw Java Thread per request ----------

    @Test
    void multipleSessionsReuseBoundedDriverThreads() throws Exception {
        Set<String> before = currentDriverThreadNames();
        ThreadPoolExecutor executor = driverExecutor();

        List<VirtualTeacherStreamingTtsClient.StreamingTask> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            SpeechSynthesizer fake = mockSynthesizer();
            VirtualTeacherStreamingTtsClient.StreamingTask task =
                    newTask(executor, List.of("段" + i), 5000, fake);
            try {
                task.start(); // 共享同一有界执行器（SynchronousQueue：前 2 个立即接手，其余立即拒绝）
            } catch (IllegalStateException rejected) {
                // 饱和时立即拒绝并失败 —— 这正是期望的 fail-fast 行为
                assertTrue(task.isFailed());
                verify(fake).close();
                continue;
            }
            task.markBodyStarted();
            tasks.add(task);
        }
        tasksToCancel.addAll(tasks);
        Thread.sleep(300);

        Set<String> after = currentDriverThreadNames();
        int newDriverThreads = after.stream()
                .filter(name -> !before.contains(name))
                .filter(name -> name.startsWith("m8-stream-driver"))
                .collect(Collectors.toSet()).size();
        // 有界执行器最多 2 个 worker：5 个会话不可能创建 5 个独立线程
        assertTrue(newDriverThreads <= 2,
                "5 个会话应复用 ≤2 个驱动线程，实际新增: " + newDriverThreads);
        assertEquals(2, executor.getMaximumPoolSize());
        assertTrue(executor.getQueue() instanceof SynchronousQueue,
                "provider 驱动必须使用 SynchronousQueue（无普通排队）");
        assertTrue(executor.getQueue().isEmpty());
    }

    // ---------- P3: cancel interrupts/unblocks segment sequencing cleanly ----------

    @Test
    void cancelUnblocksSegmentSequencingAndClosesSynthesizer() throws Exception {
        ThreadPoolExecutor executor = driverExecutor();
        SpeechSynthesizer fake = mockSynthesizer();
        // 两段：驱动会等待第一段完成（不会自动完成 → 阻塞在 monitor）
        VirtualTeacherStreamingTtsClient.StreamingTask task =
                newTask(executor, List.of("第一段", "第二段"), 5000, fake);
        tasksToCancel.add(task);

        task.start();
        task.markBodyStarted();
        Thread.sleep(100);
        assertEquals(1, executor.getActiveCount(), "驱动线程应正阻塞在段序列化上");

        task.cancel();
        // cancel 应唤醒 monitor，驱动线程干净退出
        long deadline = System.currentTimeMillis() + 3000;
        while (executor.getActiveCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(0, executor.getActiveCount(), "cancel 后驱动线程应退出");
        assertTrue(task.isCancelled());
        verify(fake).close();
    }

    // ---------- MVC 异步拒绝：body 未执行 → provider 在宽限窗口后被清理 ----------

    @Test
    void bodyNeverStartsCancelsProviderAfterGraceWindow() throws Exception {
        ThreadPoolExecutor executor = driverExecutor();
        SpeechSynthesizer fake = mockSynthesizer();
        // 100ms 宽限窗口：模拟 MVC 异步线程池拒绝 → StreamingResponseBody 从未执行
        VirtualTeacherStreamingTtsClient.StreamingTask task =
                newTask(executor, List.of("第一段", "第二段"), 100, fake);
        tasksToCancel.add(task);

        long t0 = System.nanoTime();
        task.start();
        // 不调用 markBodyStarted() —— 模拟响应体被拒绝

        long deadline = System.currentTimeMillis() + 3000;
        while (!task.isFailed() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(task.isFailed(), "body 未开始时 provider 应在宽限后失败");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        // 看门狗从 PROVIDER 准入（T0）计时：~100ms 宽限 + 短握手开销，远小于旧实现的排队延迟场景
        assertTrue(elapsedMs < 1500,
                "失败时间应锚定 provider 准入 T0（≈宽限+少量开销），实际 elapsedMs=" + elapsedMs);
        assertTrue(task.failureReason().get().contains("未在限期内"),
                "失败原因应指向 body 未启动: " + task.failureReason());
        verify(fake).close();
    }

    // ================= A2.1.1：Q1–Q4 并发正确性 =================

    // ---------- Q1: NO QUEUED DRIVER ----------

    @Test
    void q1_noQueuedDriver_occupiedWorkersRejectImmediatelyAndCloseSynthesizer() throws Exception {
        ThreadPoolExecutor executor = driverExecutor();
        // 占满全部 2 个 worker（SynchronousQueue 下无排队可能）
        occupyWorker(executor, 3000);
        occupyWorker(executor, 3000);
        Thread.sleep(100);
        assertEquals(2, executor.getActiveCount());

        SpeechSynthesizer fake = mockSynthesizer();
        VirtualTeacherStreamingTtsClient.StreamingTask task =
                newTask(executor, List.of("第一段", "第二段"), 5000, fake);
        tasksToCancel.add(task);

        long t0 = System.nanoTime();
        assertThrows(IllegalStateException.class, task::start);
        long rejectElapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        // 立即拒绝（无排队）：耗时远小于 worker 占用时间
        assertTrue(rejectElapsedMs < 1000, "应 fail-fast 拒绝，实际 " + rejectElapsedMs + "ms");
        assertTrue(task.isFailed());
        verify(fake).close();
        // 无 driver Runnable 残留排队
        assertEquals(0, executor.getQueue().size());
    }

    // ---------- Q2: BODY WATCHDOG START BOUND ----------

    @Test
    void q2_bodyWatchdogAnchoredToProviderAdmission_notDriverExecution() throws Exception {
        ThreadPoolExecutor executor = driverExecutor();
        SpeechSynthesizer fake = mockSynthesizer();
        // 短确定性宽限：80ms
        VirtualTeacherStreamingTtsClient.StreamingTask task =
                newTask(executor, List.of("第一段", "第二段"), 80, fake);
        tasksToCancel.add(task);

        long t0 = System.nanoTime();
        task.start();
        // 不调用 markBodyStarted()

        long deadline = System.currentTimeMillis() + 3000;
        while (!task.isFailed() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertTrue(task.isFailed());
        // 关键断言：失败发生在 T0+宽限 附近，而不是 T_driver_execution + 宽限。
        // 若 driver 曾排队（旧实现 core=1/queue=64），此处会显著超过上界。
        assertTrue(elapsedMs >= 60 && elapsedMs <= 800,
                "看门狗应从 provider 准入 T0 起算（宽限 80ms），实际 elapsedMs=" + elapsedMs);
        verify(fake).close();
    }

    // ---------- Q3: MULTI-SEGMENT CONCURRENCY ----------

    @Test
    void q3_multiSegmentSessionsRunIndependentDrivers() throws Exception {
        ThreadPoolExecutor executor = driverExecutor();

        SpeechSynthesizer fakeA = mockSynthesizer();
        VirtualTeacherStreamingTtsClient.StreamingTask taskA =
                newTask(executor, List.of("A1", "A2"), 5000, fakeA);
        SpeechSynthesizer fakeB = mockSynthesizer();
        VirtualTeacherStreamingTtsClient.StreamingTask taskB =
                newTask(executor, List.of("B1", "B2"), 5000, fakeB);
        tasksToCancel.add(taskA);
        tasksToCancel.add(taskB);

        taskA.start();
        taskA.markBodyStarted();
        taskB.start();
        taskB.markBodyStarted();

        // 两个已准入会话的 driver 均在活跃执行（阻塞于各自段的完成等待），无排队
        long deadline = System.currentTimeMillis() + 3000;
        while (executor.getActiveCount() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(2, executor.getActiveCount(), "两个已准入会话应各自持有活跃 driver");
        assertEquals(0, executor.getQueue().size(), "无 driver 在普通队列中等待");

        // 取消 A 不影响 B
        taskA.cancel();
        deadline = System.currentTimeMillis() + 3000;
        while (executor.getActiveCount() > 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, executor.getActiveCount(), "取消 A 后 B 的 driver 应继续");
        assertTrue(taskA.isCancelled());
        assertFalse(taskB.isCancelled());
        verify(fakeA).close();
        verify(fakeB, org.mockito.Mockito.never()).close();
    }

    // ---------- Q4: SATURATION ----------

    @Test
    void q4_saturation_failFastWithoutLiveSynthesizerOrQueuedDriver() throws Exception {
        ThreadPoolExecutor executor = driverExecutor();
        occupyWorker(executor, 3000);
        occupyWorker(executor, 3000);
        Thread.sleep(100);

        SpeechSynthesizer fake = mockSynthesizer();
        VirtualTeacherStreamingTtsClient.StreamingTask task =
                newTask(executor, List.of("第一段", "第二段"), 5000, fake);
        tasksToCancel.add(task);

        assertThrows(IllegalStateException.class, task::start);
        // 无 live synthesizer（已 close）、无 queued driver、任务失败
        verify(fake).close();
        assertEquals(0, executor.getQueue().size());
        assertTrue(task.isFailed());
        assertTrue(executor.getActiveCount() <= 2);
    }

    private Set<String> currentDriverThreadNames() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .collect(Collectors.toSet());
    }
}
