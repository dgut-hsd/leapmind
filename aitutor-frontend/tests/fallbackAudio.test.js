import { describe, test, expect, jest } from '@jest/globals';
import { LectureSpeechAdapter, SPEECH_STATE } from '../src/features/virtualTeacher/lectureSpeechAdapter.js';

/**
 * M8 FINAL ACCEPTANCE：音频兜底 ended 契约测试（FA-1 ~ FA-5）。
 * 核心不变量：
 * - 段 n 必须等段 n-1 触发 ended 之后才开始
 * - adapter 不得在最后一段 ended 之前进入 ENDED
 * - 停止/翻页/卸载必须中断进行中的兜底播放并阻止下一段
 * 使用可手动 resolve 的 promise 作为 oracle，不用任意 sleep。
 */

function makeSegments(n = 2) {
  return Array.from({ length: n }, (_, i) => ({
    audioData: btoa(`SEG${i}`),
    segmentIndex: i,
    textContent: `段${i + 1}`,
  }));
}

/**
 * 可控 fallback：play() 返回 promise，由测试决定何时"ended"。
 * 契约（与生产实现一致）：stop() 必须 settle 挂起的 play promise，防止悬挂 await。
 */
function makeControlledFallback({ failWith = null, blocked = false } = {}) {
  const calls = [];
  const pending = [];
  const fallback = {
    calls,
    play: jest.fn((arrayBuf) => {
      calls.push({ kind: 'play', at: Date.now() });
      return new Promise((resolve, reject) => {
        pending.push({ resolve, reject });
      });
    }),
    stop: jest.fn(() => {
      calls.push({ kind: 'stop', at: Date.now() });
      // 契约：中断即结束（与生产 HTMLAudioElement 实现一致）
      while (pending.length) pending.shift().resolve();
    }),
    cleanup: jest.fn(() => {
      calls.push({ kind: 'cleanup', at: Date.now() });
    }),
  };
  const completeNext = (result) => {
    const p = pending.shift();
    if (!p) return false;
    if (result === 'error') p.reject(new Error('media decode error'));
    else if (result === 'blocked') {
      const err = new Error('not allowed'); err.name = 'NotAllowedError';
      p.reject(err);
    } else p.resolve();
    return true;
  };
  return { fallback, completeNext, pending };
}

function makeAdapterWithFallback(fallback, segments = makeSegments(2)) {
  const deps = {
    getModel: () => null,
    fetchSegments: jest.fn(async () => segments),
    createFallbackAudio: () => fallback,
    onStateChange: () => {},
  };
  return new LectureSpeechAdapter(deps);
}

describe('FA-1: 段 2 不能在段 1 ended 之前开始', () => {
  test('第一段 play 后，第二段 play 不触发，直到第一段 ended', async () => {
    const { fallback, completeNext } = makeControlledFallback();
    const adapter = makeAdapterWithFallback(fallback);
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1 });

    const p = adapter.start();
    // 等待第一段 play 被调用
    await new Promise((r) => setTimeout(r, 0));
    expect(fallback.calls.filter((c) => c.kind === 'play')).toHaveLength(1);

    // 第一段尚未 ended：第二段绝不能开始
    expect(fallback.calls.filter((c) => c.kind === 'play')).toHaveLength(1);
    expect(adapter.getState()).not.toBe(SPEECH_STATE.ENDED);

    // 第一段 ended
    completeNext();
    await new Promise((r) => setTimeout(r, 0));
    // 第二段开始
    expect(fallback.calls.filter((c) => c.kind === 'play')).toHaveLength(2);

    completeNext(); // 第二段 ended
    await p;
    expect(adapter.getState()).toBe(SPEECH_STATE.ENDED);
  });
});

describe('FA-2: 不能在最后一段 ended 之前进入 ENDED', () => {
  test('最后一段播放中 → 状态非 ENDED；ended 后才 ENDED', async () => {
    const { fallback, completeNext } = makeControlledFallback();
    const adapter = makeAdapterWithFallback(fallback, makeSegments(1));
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1 });

    const p = adapter.start();
    await new Promise((r) => setTimeout(r, 0));
    expect(fallback.calls.filter((c) => c.kind === 'play')).toHaveLength(1);
    expect(adapter.getState()).not.toBe(SPEECH_STATE.ENDED); // 播放中，未 ENDED

    completeNext();
    await p;
    expect(adapter.getState()).toBe(SPEECH_STATE.ENDED); // ended 后才 ENDED
  });
});

describe('FA-3: 翻页停止当前兜底并阻止下一段', () => {
  test('bindSlide 新 slide → stop 被调用，且不再播放剩余段', async () => {
    const { fallback, completeNext } = makeControlledFallback();
    const adapter = makeAdapterWithFallback(fallback, makeSegments(3));
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1 });

    const p = adapter.start();
    await new Promise((r) => setTimeout(r, 0));
    expect(fallback.calls.filter((c) => c.kind === 'play')).toHaveLength(1);

    // 段 1 播放中翻页
    adapter.bindSlide({ courseId: 'c1', slideId: 's2', pageNumber: 2 });
    await new Promise((r) => setTimeout(r, 0));

    expect(fallback.stop).toHaveBeenCalled();
    // 剩余段绝不再 play
    expect(fallback.calls.filter((c) => c.kind === 'play')).toHaveLength(1);
    // 旧 start promise 正常收尾（被作废，不 ENDED）
    await p;
    expect(adapter.getState()).toBe(SPEECH_STATE.IDLE); // bindSlide 后为 IDLE
    void completeNext;
  });
});

describe('FA-4: media error → ERROR', () => {
  test('play() reject 非 autoplay 错误 → 进入 ERROR', async () => {
    const { fallback, completeNext } = makeControlledFallback();
    const adapter = makeAdapterWithFallback(fallback, makeSegments(1));
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1 });

    const p = adapter.start();
    await new Promise((r) => setTimeout(r, 0));
    completeNext('error'); // 第一段解码错误
    await p;

    expect(adapter.getState()).toBe(SPEECH_STATE.ERROR);
  });
});

describe('FA-5: NotAllowedError → AUTOPLAY_BLOCKED', () => {
  test('play() 被浏览器 autoplay 策略拒绝 → AUTOPLAY_BLOCKED，非 ERROR', async () => {
    const { fallback, completeNext } = makeControlledFallback();
    const adapter = makeAdapterWithFallback(fallback, makeSegments(1));
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1 });

    const p = adapter.start();
    await new Promise((r) => setTimeout(r, 0));
    completeNext('blocked');
    await p;

    expect(adapter.getState()).toBe(SPEECH_STATE.AUTOPLAY_BLOCKED);
    expect(adapter.getState()).not.toBe(SPEECH_STATE.ERROR);
  });
});
