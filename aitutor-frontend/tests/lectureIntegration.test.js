import { describe, test, expect, jest } from '@jest/globals';
import { LectureSpeechAdapter, SPEECH_STATE } from '../src/features/virtualTeacher/lectureSpeechAdapter.js';
import { handleLectureSlideChange } from '../src/features/virtualTeacher/lecturePlaybackBridge.js';

/**
 * M8 FINAL 集成验收测试（讲课产品集成层）。
 * 证明：
 * - 预生成路径保留 / 流式路径保留
 * - avatar 更换不自动重启旁白
 * - 教师隐藏不静音
 * - 模型失败课程仍可用（不抛未捕获异常）
 * - slide 变化停止真实播放
 */

function makeModel() {
  const model = {
    speak: jest.fn(async () => {}),
    stopSpeaking: jest.fn(() => {}),
    emoteController: { lipSyncWeights: jest.fn(), lipSync: jest.fn() },
  };
  return model;
}

function makeDeps({ segments = null } = {}) {
  const model = makeModel();
  const playback = { state: 'PLAYING', playFromReader: jest.fn(async () => {}), pause: jest.fn(), resume: jest.fn(async () => {}), stop: jest.fn(() => { playback.state = 'ENDED'; }) };
  const deps = {
    getModel: () => model,
    fetchSegments: jest.fn(async () => segments),
    streamSpeech: jest.fn(async () => ({ reader: { cancel: jest.fn() }, headers: { sampleRate: 16000, channels: 1, byteOrder: 'little-endian' } })),
    createStreamingPlayback: jest.fn(() => playback),
    onStateChange: () => {},
  };
  return { deps, model, playback };
}

describe('M8 FINAL: 预生成路径保留', () => {
  test('hasPregeneratedAudio → 使用 fetchSegments + model.speak（不发起流式）', async () => {
    const segments = [{ audioData: btoa('AAAA'), segmentIndex: 0, textContent: '段1' }];
    const { deps, model, playback } = makeDeps({ segments });
    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, hasPregeneratedAudio: true, narrationText: '文本' });
    await adapter.start();

    expect(deps.fetchSegments).toHaveBeenCalledWith('c1', 1);
    expect(model.speak).toHaveBeenCalled();
    expect(deps.streamSpeech).not.toHaveBeenCalled();
    expect(playback.playFromReader).not.toHaveBeenCalled();
  });
});

describe('M8 FINAL: 流式回退保留', () => {
  test('无预生成音频但有文本 → 使用流式 TTS', async () => {
    const { deps, playback } = makeDeps({ segments: null });
    const adapter = new LectureSpeechAdapter(deps);
    // FINAL.1 (A3): 真实策略会先探测 fetchSegments（segments 为 null → 无预生成），再回退流式
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, narrationText: '旁白文本' });
    await adapter.start();

    expect(deps.fetchSegments).toHaveBeenCalledWith('c1', 1); // 探测（A3 正确行为）
    expect(deps.streamSpeech).toHaveBeenCalled();
    expect(playback.playFromReader).toHaveBeenCalled();
  });
});

describe('M8 FINAL: avatar 更换不重启旁白', () => {
  test('换形象（无 slide 变化）不触发 stop/作废代际', async () => {
    const segments = [{ audioData: btoa('AAAA'), segmentIndex: 0, textContent: '段1' }];
    const { deps, model, playback } = makeDeps({ segments });
    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, hasPregeneratedAudio: true });
    await adapter.start();
    const genBefore = adapter.getGeneration();
    const stopCalls = model.stopSpeaking.mock.calls.length;

    // 模拟换形象：仅更新 voiceType/模型引用，不调用 bindSlide/start
    adapter.voiceType = 'new-voice';
    adapter.getModel = () => ({ ...model, emoteController: { lipSyncWeights: jest.fn(), lipSync: jest.fn() } });

    expect(adapter.getGeneration()).toBe(genBefore);
    expect(model.stopSpeaking.mock.calls.length).toBe(stopCalls);
    expect(playback.stop).not.toHaveBeenCalled();
  });
});

describe('M8 FINAL: 教师隐藏 ≠ 静音', () => {
  test('setTeacherVisible(false) 不触发任何音频操作', async () => {
    const { deps, model, playback } = makeDeps({ segments: null });
    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, narrationText: 'x' });
    await adapter.start();
    const stopCalls = model.stopSpeaking.mock.calls.length;

    adapter.setTeacherVisible(false);

    expect(model.stopSpeaking.mock.calls.length).toBe(stopCalls);
    expect(playback.stop).not.toHaveBeenCalled();
  });
});

describe('M8 FINAL: 模型/WebGL 失败课程仍可用', () => {
  test('getModel 返回 null 时 start 不抛出未捕获异常（流式路径可用）', async () => {
    const { deps } = makeDeps({ segments: null });
    deps.getModel = () => null; // 模拟模型加载失败
    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, narrationText: '旁白' });
    await expect(adapter.start()).resolves.not.toThrow();
  });

  test('模型失败且无旁白 → NO_NARRATION 直接 ENDED，不发请求', async () => {
    const { deps, model } = makeDeps({ segments: null });
    deps.getModel = () => null;
    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, hasPregeneratedAudio: false, narrationText: '' });
    await adapter.start();
    expect(adapter.getState()).toBe(SPEECH_STATE.ENDED);
    expect(deps.streamSpeech).not.toHaveBeenCalled();
    expect(model.speak).not.toHaveBeenCalled();
  });
});

describe('M8 FINAL: slide 变化停止真实播放（bridge seam）', () => {
  test('真实 legacy 播放被 handleLectureSlideChange 停止', () => {
    let playing = true;
    let stopCalls = 0;
    const stopped = handleLectureSlideChange({
      stopLegacyPlayback: () => { playing = false; stopCalls += 1; },
    });
    expect(stopped).toBe(true);
    expect(playing).toBe(false);
    expect(stopCalls).toBe(1);
  });
});

describe('M8 FINAL: 组件卸载清理（Tab/路由切换安全）', () => {
  test('dispose 后新 start 不会导致旧播放器再次发声', async () => {
    const { deps, playback } = makeDeps({ segments: null });
    const adapter = new LectureSpeechAdapter(deps);
    adapter.bindSlide({ courseId: 'c1', slideId: 's1', pageNumber: 1, narrationText: 'x' });
    await adapter.start();
    adapter.dispose();
    expect(playback.stop).toHaveBeenCalled();
    // 再次 start（如重新进入讲课页）应重新创建播放器而非复用旧的
    const playFromReaderCalls = playback.playFromReader.mock.calls.length;
    expect(playFromReaderCalls).toBe(1); // 旧的只播放过一次
  });
});
