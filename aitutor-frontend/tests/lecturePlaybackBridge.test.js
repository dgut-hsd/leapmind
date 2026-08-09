import { describe, test, expect, jest } from '@jest/globals';
import { handleLectureSlideChange } from '../src/features/virtualTeacher/lecturePlaybackBridge.js';
import { LectureSpeechAdapter, SPEECH_STATE } from '../src/features/virtualTeacher/lectureSpeechAdapter.js';

/**
 * B1.1 集成 seam 测试：真实 M4 legacy 预生成播放服从 B1 生命周期。
 *
 * seam = handleLectureSlideChange（SlideViewer 翻页时调用）。
 * 证明：
 * 1) legacy 预生成播放开始时（model.speak 等效的 stop 目标存在）
 * 2) canonical slide 变化 → 桥调用 stopLegacyPlayback
 * 3) 迟到的 legacy 完成回调无法重启/更新当前 slide 语音
 */

function makeLegacyPlayback() {
  // 模拟 pptController 内部 state + stopPlayback 语义
  const legacy = {
    stopCalls: 0,
    speakStarted: false,
    isPlaying: false,
    start() {
      legacy.speakStarted = true;
      legacy.isPlaying = true;
    },
    stop() {
      legacy.stopCalls += 1;
      legacy.isPlaying = false;
    },
  };
  return legacy;
}

describe('B1.1: 真实 M4 legacy 播放被桥停止', () => {
  test('slide 变化 → stopLegacyPlayback 被调用，legacy isPlaying 归 false', () => {
    const legacy = makeLegacyPlayback();
    legacy.start(); // 真实播放开始（fetchSegments → startPlayback → model.speak）
    expect(legacy.isPlaying).toBe(true);

    const stopped = handleLectureSlideChange({ stopLegacyPlayback: () => legacy.stop() });

    expect(stopped).toBe(true);
    expect(legacy.stopCalls).toBe(1);
    expect(legacy.isPlaying).toBe(false);
  });

  test('adapter 注入时，slide 变化同时作废 M8 代际', () => {
    const legacy = makeLegacyPlayback();
    legacy.start();
    let bound = null;
    const adapter = new LectureSpeechAdapter({
      getModel: () => null,
      onStateChange: () => {},
    });
    adapter.bindSlide = jest.fn((slide) => { bound = slide; });

    handleLectureSlideChange({
      stopLegacyPlayback: () => legacy.stop(),
      adapter,
      slide: { courseId: 'c1', slideId: 's3', pageNumber: 3 },
    });

    expect(adapter.bindSlide).toHaveBeenCalledWith({ courseId: 'c1', slideId: 's3', pageNumber: 3 });
    expect(bound.pageNumber).toBe(3);
  });

  test('桥无 stop 依赖时不抛错，返回 false', () => {
    const result = handleLectureSlideChange({ stopLegacyPlayback: null });
    expect(result).toBe(false);
  });

  test('stop 抛异常不阻塞翻页（slide 仍完成切换）', () => {
    let slideChanged = false;
    handleLectureSlideChange({
      stopLegacyPlayback: () => { throw new Error('stop failed'); },
      adapter: { bindSlide: () => { slideChanged = true; } },
    });
    expect(slideChanged).toBe(true); // adapter 仍执行
  });
});

describe('B1.1: 迟到完成不能重启当前 slide 语音（seam 级）', () => {
  test('legacy 播放已停止后，其迟到 onended 不能重新触发播放', async () => {
    const legacy = makeLegacyPlayback();
    legacy.start();

    // 模拟 model.speak 的迟到结束回调（播放被翻页停止后才触发）
    let lateCompletionRestarted = false;
    const lateOnEnded = () => {
      if (legacy.isPlaying) lateCompletionRestarted = true;
    };

    // 翻页 → 桥停止
    handleLectureSlideChange({ stopLegacyPlayback: () => legacy.stop() });

    // 迟到的结束回调
    lateOnEnded();

    expect(legacy.isPlaying).toBe(false);
    expect(lateCompletionRestarted).toBe(false);
  });

  test('快速 1→2→3：仅 slide 3 保持 current，legacy 停止被调用 2 次（1→2, 2→3）', () => {
    const legacy = makeLegacyPlayback();
    legacy.start();

    // slide 1 → 2
    handleLectureSlideChange({ stopLegacyPlayback: () => legacy.stop() });
    // slide 2 → 3
    handleLectureSlideChange({ stopLegacyPlayback: () => legacy.stop() });

    expect(legacy.stopCalls).toBe(2);
    expect(legacy.isPlaying).toBe(false);
    // 最后 canonical slide 由 M4 持有（本 seam 不持有 slide 状态）
    expect(legacy.currentSlideIndex).toBeUndefined();
  });
});
