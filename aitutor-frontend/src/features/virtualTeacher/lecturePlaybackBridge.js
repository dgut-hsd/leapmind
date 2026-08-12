/**
 * lecturePlaybackBridge — M4 legacy 播放与 B1 生命周期的集成桥（Phase B1.1）。
 *
 * 问题（B1 遗留）：
 * 真实 M4 预生成播放路径是
 *   SlideViewer.handleRemotePlay
 *     → fetchSegments → playerState
 *     → pptController.startPlayback()
 *     → playCurrentSegment() → sharedViewer.model.speak(...)
 * 该路径完全不经过 LectureSpeechAdapter。
 * B1 只保证了 M8 侧（流式/自建播放）服从 slide 生命周期；
 * legacy 预生成播放不会因翻页被停止 → 声音跨页残留。
 *
 * 本桥的职责：
 * - 在 canonical slide 变化时，显式停止 legacy 预生成播放（pptController.stopPlayback）
 * - 同时作废 M8 侧 adapter 代际（若已注入）
 * 不重写 pptController、不删除预生成路径、不接管 M4 导航。
 *
 * 依赖注入：legacy stop 与 adapter 由调用方传入，便于 node 环境独立测试。
 */

/**
 * canonical slide 变化时的统一处理（集成 seam）。
 * 调用时机：M4 翻页（NEXT/PREV/缩略图跳转）实际发生时。
 *
 * @param {Object} opts
 * @param {Function|null} [opts.stopLegacyPlayback] 停止 legacy 预生成播放的调用（如 pptController.stopPlayback）
 * @param {Object|null} [opts.adapter] LectureSpeechAdapter 实例（可选；注入后同步作废 M8 代际）
 * @param {Object|null} [opts.slide] 新 slide 描述（可选；绑定给 adapter）
 * @returns {boolean} legacy 停止是否被实际调用
 */
export function handleLectureSlideChange({
  stopLegacyPlayback = null,
  adapter = null,
  slide = null,
} = {}) {
  let legacyStopped = false;
  try {
    if (typeof stopLegacyPlayback === 'function') {
      stopLegacyPlayback();
      legacyStopped = true;
    }
  } catch (_) {
    // 停止失败不阻塞翻页
  }
  try {
    if (adapter && typeof adapter.bindSlide === 'function') {
      adapter.bindSlide(slide);
    }
  } catch (_) {
    // adapter 异常不阻塞翻页
  }
  return legacyStopped;
}
