import { describe, test, expect, jest } from '@jest/globals';
import {
  LectureSpeechAdapter,
  decideAudioSource,
  SPEECH_SOURCE,
  SPEECH_STATE,
} from '../src/features/virtualTeacher/lectureSpeechAdapter.js';

/**
 * B1-1 ~ B1-10 讲课语音生命周期测试（Phase B1）。
 * 全部使用依赖注入 + mock，不触碰真实 DOM / WebAudio / 网络。
 * 核心目标：证明旧 slide 的迟到结果不可能影响新 slide 的播放/UI。
 */

function makeModel() {
  const calls = [];
  const model = {
    calls,
    speak: jest.fn(async (buffer, screenplay) => {
      calls.push({ type: 'speak', buffer, screenplay });
    }),
    stopSpeaking: jest.fn(() => {
      calls.push({ type: 'stopSpeaking' });
    }),
  };
  return model;
}

function makeStreamingPlaybackStub() {
  const playback = {
    state: 'PLAYING',
    playFromReader: jest.fn(async () => {}),
    pause: jest.fn(),
    resume: jest.fn(async () => {}),
    stop: jest.fn(() => {
      playback.state = 'ENDED';
    }),
    setStateHook: null,
  };
  return playback;
}

function makeDeps({ onStateChange, segments = null, reader = null, headers = {} } = {}) {
  const states = [];
  const model = makeModel();
  const playback = makeStreamingPlaybackStub();
  const deps = {
    getModel: () => model,
    fetchSegments: jest.fn(async () => segments),
    streamSpeech: jest.fn(async () => ({ reader: reader ?? { cancel: jest.fn() }, headers })),
    createStreamingPlayback: jest.fn((opts) => {
      if (opts.onStateChange) playback.setStateHook = opts.onStateChange;
      return playback;
    }),
    onStateChange: (s) => {
      states.push(s);
      onStateChange?.(s);
    },
  };
  return { deps, model, playback, states };
}

describe('decideAudioSource 音频源策略（B1-5/B1-6/B1-7）', () => {
  test('B1-5: 有效预生成音频存在 → 选择 PREGENERATED_AUDIO', () => {
    expect(decideAudioSource({ hasPregeneratedAudio: true, hasNarrationText: true }))
      .toBe(SPEECH_SOURCE.PREGENERATED_AUDIO);
    expect(decideAudioSource({ hasPregeneratedAudio: true, hasNarrationText: false }))
      .toBe(SPEECH_SOURCE.PREGENERATED_AUDIO);
  });

  test('B1-6: 无预生成音频但有文本 → 选择 M8_STREAMING_TTS', () => {
    expect(decideAudioSource({ hasPregeneratedAudio: false, hasNarrationText: '旁白文本' }))
      .toBe(SPEECH_SOURCE.M8_STREAMING_TTS);
  });

  test('B1-7: 无音频且无文本 → NO_NARRATION（不发请求）', () => {
    expect(decideAudioSource({ hasPregeneratedAudio: false, hasNarrationText: '' }))
      .toBe(SPEECH_SOURCE.NO_NARRATION);
  });
});

describe('B1-1: slide 变化时旧流式请求被取消/作废', () => {
  test('bindSlide 新 slide 递增代际且停止旧播放器', async () => {
    const { deps, playback } = makeDeps({ reader: { cancel: jest.fn() } });
    const adapter = new LectureSpeechAdapter(deps);

    await adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, narrationText: '第一页' });
    const gen1 = adapter.getGeneration();
    await adapter.start();

    // slide 1 播放中 → 切换到 slide 2
    await adapter.bindSlide({ courseId: 'c1', slideId: 's2', pageNumber: 2, narrationText: '第二页' });
    const gen2 = adapter.getGeneration();

    expect(gen2).toBeGreaterThan(gen1);
    expect(playback.stop).toHaveBeenCalled(); // 旧流式播放器被停止
    expect(adapter.getState()).toBe(SPEECH_STATE.IDLE); // 不自动开始新旁白
  });
});

describe('B1-2: 旧请求在新 slide 之后才返回 → 不能播放', () => {
  test('start 前 slide 已变化，旧 streamSpeech 结果被丢弃并取消 reader', async () => {
    let resolveStream;
    const cancel = jest.fn();
    const { deps, playback } = makeDeps({ reader: { cancel } });
    deps.streamSpeech = jest.fn(() => new Promise((resolve) => { resolveStream = resolve; }));

    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, narrationText: '旧文本' });
    const startPromise = adapter.start();

    // FINAL.1 (A3): start() 先探测 fetchSegments（异步微任务）再进入 streamSpeech；
    // 等待探测完成进入流式挂起后，用户切到 slide 2（作废代际）
    await new Promise((r) => setTimeout(r, 0));
    adapter.bindSlide({ courseId: 'c1', slideId: 's2', pageNumber: 2, narrationText: '新文本' });

    // 旧流此刻才返回
    resolveStream({ reader: { cancel }, headers: { sampleRate: 16000, channels: 1, byteOrder: 'little-endian' } });
    await startPromise;

    expect(cancel).toHaveBeenCalled(); // 旧代际 reader 被取消
    expect(playback.playFromReader).not.toHaveBeenCalled(); // 旧音频绝不进入播放管线
  });

  test('预生成路径：fetch 返回时代际已变 → 不调用 model.speak', async () => {
    let resolveFetch;
    const segments = [{ audioData: btoa('AAAA'), segmentIndex: 0, textContent: '旧段' }];
    const { deps, model } = makeDeps({ segments });
    deps.fetchSegments = jest.fn(() => new Promise((resolve) => { resolveFetch = resolve; }));

    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, hasPregeneratedAudio: true });
    const p = adapter.start();

    adapter.bindSlide({ courseId: 'c1', slideId: 's2', pageNumber: 2, hasPregeneratedAudio: true });
    resolveFetch(segments);
    await p;

    expect(model.speak).not.toHaveBeenCalled();
  });
});

describe('B1-3: 正在播放 + NEXT → 旧播放停止', () => {
  test('bindSlide 新 slide 触发旧预生成播放中断（stopSpeaking）', async () => {
    const segments = [
      { audioData: btoa('AAAA'), segmentIndex: 0, textContent: '段1' },
      { audioData: btoa('BBBB'), segmentIndex: 1, textContent: '段2' },
    ];
    const { deps, model } = makeDeps({ segments });
    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, hasPregeneratedAudio: true });

    const p = adapter.start();
    // 第一段播放中（model.speak 尚未 resolve）用户 NEXT
    await new Promise((r) => setTimeout(r, 0));
    adapter.bindSlide({ courseId: 'c1', slideId: 's2', pageNumber: 2, hasPregeneratedAudio: true });
    await p;

    expect(model.stopSpeaking).toHaveBeenCalled();
  });
});

describe('B1-4: 快速 1→2→3 只有 3 能影响语音状态', () => {
  test('三个代际中仅最新代际的流式结果进入播放', async () => {
    const resolvers = [];
    const cancels = [];
    const { deps, playback } = makeDeps({});
    deps.streamSpeech = jest.fn(() => {
      const idx = resolvers.length;
      const cancel = jest.fn();
      cancels.push(cancel);
      return new Promise((resolve) => {
        resolvers.push((headers) => resolve({ reader: { cancel }, headers }));
      });
    });

    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, narrationText: '1' });
    const p1 = adapter.start();
    adapter.bindSlide({ courseId: 'c1', slideId: 's2', pageNumber: 2, narrationText: '2' });
    const p2 = adapter.start();
    adapter.bindSlide({ courseId: 'c1', slideId: 's3', pageNumber: 3, narrationText: '3' });
    const p3 = adapter.start();

    // FINAL.1 (A3): 每次 start 先异步探测 fetchSegments；探测后旧代际（1/2）已被新 bindSlide 作废，
    // 只有最新代际 3 能继续进入流式挂起。等待探测完成（微任务）。
    await new Promise((r) => setTimeout(r, 0));

    // 只有代际 3 到达了 streamSpeech（1/2 在探测阶段已被作废，未发起流式请求）
    expect(deps.streamSpeech).toHaveBeenCalledTimes(1);
    expect(cancels.length).toBe(1);
    resolvers[0]({ sampleRate: 16000, channels: 1, byteOrder: 'little-endian' });
    await Promise.all([p1, p2, p3]);

    // 只有代际 3 的 reader 进入播放管线
    expect(playback.playFromReader).toHaveBeenCalledTimes(1);
    expect(cancels[0]).not.toHaveBeenCalled(); // 最新代际的 reader 不被取消
  });
});

describe('B1-8: 组件卸载 → 流式生命周期取消', () => {
  test('dispose 停止播放器、作废代际、清 slide', async () => {
    const { deps, playback } = makeDeps({ reader: { cancel: jest.fn() } });
    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, narrationText: '文本' });
    await adapter.start();
    const genBefore = adapter.getGeneration();

    adapter.dispose();

    expect(playback.stop).toHaveBeenCalled();
    expect(adapter.getGeneration()).toBeGreaterThan(genBefore);
    expect(adapter.getState()).toBe(SPEECH_STATE.IDLE);
    // 不销毁共享 viewer（无 dispose viewer 调用）
    expect(deps.getModel()).toBeTruthy();
  });
});

describe('B1-9: 隐藏教师 ≠ 静音/停止音频', () => {
  test('setTeacherVisible(false) 不触发任何音频操作', async () => {
    const { deps, playback } = makeDeps({ reader: { cancel: jest.fn() } });
    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, narrationText: '文本' });
    await adapter.start();

    // 记录 bindSlide/start 已产生的清理调用，setTeacherVisible 必须不新增任何调用
    const stopCallsAfterSetup = deps.getModel().stopSpeaking.mock.calls.length;
    const playbackStopCallsAfterSetup = playback.stop.mock.calls.length;
    const pauseCallsAfterSetup = playback.pause.mock.calls.length;

    adapter.setTeacherVisible(false);

    expect(playback.stop.mock.calls.length).toBe(playbackStopCallsAfterSetup);
    expect(playback.pause.mock.calls.length).toBe(pauseCallsAfterSetup);
    expect(deps.getModel().stopSpeaking.mock.calls.length).toBe(stopCallsAfterSetup);
  });
});

describe('B1-10: M4 仍是 slide/导航状态 owner', () => {
  test('adapter 不暴露 next/prev 导航方法，bindSlide 仅记录身份', () => {
    const { deps } = makeDeps({});
    const adapter = new LectureSpeechAdapter(deps);
    expect(adapter.next).toBeUndefined();
    expect(adapter.previous).toBeUndefined();
    expect(adapter.goTo).toBeUndefined();

    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, narrationText: 'x' });
    expect(adapter.slide).toEqual({ courseId: 'c1', slideId: 's1', pageNumber: 1, narrationText: 'x' });
    // adapter 不持有 slide 列表/索引
    expect(adapter.slides).toBeUndefined();
    expect(adapter.currentSlideIndex).toBeUndefined();
  });
});
