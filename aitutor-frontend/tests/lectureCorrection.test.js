import { describe, test, expect, jest } from '@jest/globals';
import { LectureSpeechAdapter, SPEECH_STATE } from '../src/features/virtualTeacher/lectureSpeechAdapter.js';

/**
 * M8 FINAL POST-CHECKPOINT CORRECTION 测试（V1-V4 / S1-S5）。
 * V: 已保存 preference 音色同步；S: 预生成 segments 单次 fetch。
 */

function makeModel() {
  return {
    speak: jest.fn(async () => {}),
    stopSpeaking: jest.fn(() => {}),
    emoteController: { lipSyncWeights: jest.fn(), lipSync: jest.fn() },
  };
}

function makeDeps({ segments = null, fetchImpl = null } = {}) {
  const model = makeModel();
  const playback = {
    state: 'PLAYING',
    playFromReader: jest.fn(async () => {}),
    pause: jest.fn(() => { playback.state = 'PAUSED'; }),
    resume: jest.fn(async () => { playback.state = 'PLAYING'; }),
    stop: jest.fn(() => { playback.state = 'ENDED'; }),
  };
  const deps = {
    getModel: () => model,
    fetchSegments: fetchImpl || jest.fn(async () => segments),
    streamSpeech: jest.fn(async () => ({ reader: { cancel: jest.fn() }, headers: { sampleRate: 16000, channels: 1, byteOrder: 'little-endian' } })),
    createStreamingPlayback: jest.fn(() => playback),
    onStateChange: () => {},
  };
  return { deps, model, playback };
}

// ── V1-V4: 已保存 preference 音色同步 ──────────────────────────
describe('V: saved preference 音色同步到 adapter', () => {
  test('V1: adapter 创建后 preference 到达 → 未来音色变为 B', () => {
    const { deps } = makeDeps({ segments: null });
    const adapter = new LectureSpeechAdapter(deps);
    // 页面首次 render：selectedAvatar=null → voiceType default（构造时）
    expect(adapter.voiceType).toBe('default');

    // preference 异步到达后，页面 effect 调用 adapter.setVoiceType(pref.voiceType)
    adapter.setVoiceType('young-female-clear');

    expect(adapter.voiceType).toBe('young-female-clear');
  });

  test('V2: 音色同步不改变代际', () => {
    const { deps } = makeDeps({ segments: null });
    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, title: 'x' });
    const gen = adapter.getGeneration();
    adapter.setVoiceType('voice-b');
    expect(adapter.getGeneration()).toBe(gen);
  });

  test('V3: 音色同步不停止当前播放', async () => {
    const { deps, model } = makeDeps({ segments: null });
    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, title: 'x' });
    await adapter.start();
    const stopCalls = model.stopSpeaking.mock.calls.length;
    adapter.setVoiceType('voice-c');
    expect(model.stopSpeaking.mock.calls.length).toBe(stopCalls);
  });

  test('V4: 下一次同页流式 start 使用新音色 B', async () => {
    const { deps } = makeDeps({ segments: [] });
    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, title: '旁白' });
    adapter.setVoiceType('voice-b');
    await adapter.start();
    expect(deps.streamSpeech).toHaveBeenCalledWith(expect.objectContaining({ voiceType: 'voice-b' }));
  });
});

// ── S1-S5: 预生成 segments 单次 fetch ──────────────────────────
describe('S: fetchSegments 至多一次', () => {
  test('S1: 远程 slide + 有效 segments → fetchSegments 恰好一次', async () => {
    const segments = [{ audioData: btoa('AAAA'), segmentIndex: 0, textContent: '段1' }];
    const { deps } = makeDeps({ segments });
    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 'remote-1', pageNumber: 1, title: '标题' });
    await adapter.start();
    expect(deps.fetchSegments).toHaveBeenCalledTimes(1);
  });

  test('S2: 探测到的精确结果进入播放（不依赖第二次 fetch）', async () => {
    // 第二次 fetch 若被调用会失败 —— 若实现正确，永远到不了第二次
    let calls = 0;
    const { deps, model } = makeDeps({
      fetchImpl: jest.fn(async () => {
        calls += 1;
        if (calls > 1) throw new Error('不应发生第二次 fetch');
        return [{ audioData: btoa('AAAA'), segmentIndex: 0, textContent: '段1' }];
      }),
    });
    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 'remote-1', pageNumber: 1, title: '标题' });
    await adapter.start();
    expect(calls).toBe(1);
    expect(model.speak).toHaveBeenCalled();
  });

  test('S3: 有有效 segments → 无流式请求', async () => {
    const segments = [{ audioData: btoa('AAAA'), segmentIndex: 0, textContent: '段1' }];
    const { deps } = makeDeps({ segments });
    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 'remote-1', pageNumber: 1, title: '标题' });
    await adapter.start();
    expect(deps.streamSpeech).not.toHaveBeenCalled();
  });

  test('S4: 首次结果为空 + 有文本 → 一次 fetch + 流式回退', async () => {
    const { deps, playback } = makeDeps({ segments: [] });
    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 'remote-1', pageNumber: 1, title: '旁白文本' });
    await adapter.start();
    expect(deps.fetchSegments).toHaveBeenCalledTimes(1);
    expect(deps.streamSpeech).toHaveBeenCalled();
    expect(playback.playFromReader).toHaveBeenCalled();
  });

  test('S5: 探测失败 + 有文本 → 流式回退（一次 fetch 尝试）', async () => {
    const { deps, playback } = makeDeps({
      fetchImpl: jest.fn(async () => { throw new Error('network down'); }),
    });
    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 'remote-1', pageNumber: 1, title: '旁白文本' });
    await adapter.start();
    expect(deps.fetchSegments).toHaveBeenCalledTimes(1);
    expect(deps.streamSpeech).toHaveBeenCalled();
    expect(playback.playFromReader).toHaveBeenCalled();
    // 探测失败走流式回退：不得进入 ERROR（真实 StreamingPlayback 会经状态回调推进到 PLAYING）
    expect(adapter.getState()).not.toBe(SPEECH_STATE.ERROR);
  });
});
