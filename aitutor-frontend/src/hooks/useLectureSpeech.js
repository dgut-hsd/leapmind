/**
 * useLectureSpeech — M8 讲课语音适配器的 React 绑定（Phase B1）。
 *
 * 用途：
 * - 在 M4 讲课页中挂载，监听 canonical slide 变化
 * - slide 变化时自动调用 adapter.bindSlide(...)（作废旧代际 + 停止旧音频）
 * - 不自动开始旁白（M4 保持用户触发语义）；由页面显式调用 start()
 *
 * 状态所有权：
 * - M4 仍然拥有 currentSlide/导航/进度；本 hook 只消费 slide 描述
 * - 不产生第二份 slide 状态
 */
import { useEffect, useMemo, useRef } from 'react';
import { LectureSpeechAdapter, SPEECH_STATE } from '../features/virtualTeacher/lectureSpeechAdapter.js';

/**
 * @param {Object} options
 * @param {Function} [options.getModel] 返回当前 VRM 模型（含 speak/stopSpeaking）。由 M4 集成点注入
 *   （如 () => sharedViewer?.model || null）；不注入时口型/预生成路径不可用但流式仍可工作。
 * @param {Function} [options.createAdapter] 完整依赖注入工厂（优先于 getModel，便于测试）
 * @param {Object} [options.slide] 当前 slide 描述 {courseId, slideId, pageNumber, hasPregeneratedAudio, narrationText, voiceType}
 * @param {(state: string) => void} [options.onStateChange]
 * @param {(text: string) => void} [options.onSubtitle]
 * @returns {{ adapter: LectureSpeechAdapter|null, start: Function, stop: Function, pause: Function, resume: Function, replay: Function, state: string, setTeacherVisible: Function }}
 */
export function useLectureSpeech({
  getModel = null,
  createAdapter = null,
  slide = null,
  onStateChange = () => {},
  onSubtitle = () => {},
} = {}) {
  const adapterRef = useRef(null);
  const stateRef = useRef(SPEECH_STATE.IDLE);
  const onStateChangeRef = useRef(onStateChange);
  const onSubtitleRef = useRef(onSubtitle);
  const slideRef = useRef(slide);

  // 保持最新回调，避免 adapter 内部捕获旧闭包
  onStateChangeRef.current = onStateChange;
  onSubtitleRef.current = onSubtitle;
  slideRef.current = slide;

  // 惰性创建适配器（依赖注入工厂优先）
  if (adapterRef.current === null) {
    adapterRef.current = createAdapter
      ? createAdapter()
      : new LectureSpeechAdapter({
          getModel,
          onStateChange: (state, info) => {
            stateRef.current = state;
            try { onStateChangeRef.current(state, info); } catch (_) {}
          },
          onSubtitle: (text) => {
            try { onSubtitleRef.current(text); } catch (_) {}
          },
        });
  }

  const adapter = adapterRef.current;

  // slide 变化 → 绑定新身份并作废旧代际（不自动播放）
  useEffect(() => {
    if (!adapter) return;
    const current = slideRef.current;
    adapter.bindSlide(current || null);
    // 依赖数组刻意使用 slide 引用字段，避免每次渲染都触发
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [adapter, slide?.courseId, slide?.slideId, slide?.pageNumber]);

  // 卸载 → 清理流式生命周期（reader/播放器/代际）；不销毁共享 viewer
  useEffect(() => {
    const a = adapter;
    return () => {
      try { a.dispose(); } catch (_) {}
    };
  }, [adapter]);

  const actions = useMemo(() => {
    if (!adapter) {
      return { start: async () => {}, stop: () => {}, pause: () => {}, resume: async () => {}, replay: async () => {}, setTeacherVisible: () => {} };
    }
    return {
      start: () => adapter.start(),
      stop: () => adapter.stop(),
      pause: () => adapter.pause(),
      resume: () => adapter.resume(),
      replay: () => adapter.replay(),
      setTeacherVisible: (v) => adapter.setTeacherVisible(v),
    };
  }, [adapter]);

  return {
    adapter,
    ...actions,
    state: stateRef.current,
  };
}
