/**
 * M8 讲课语音适配器（Phase B1 + FINAL.1）。
 *
 * 职责（M8 侧最小集成层，介于 M4 slide 状态与 M8 语音播放之间）：
 * - 绑定当前 slide 身份（courseId / slideId / pageNumber），与请求代际关联
 * - 真实音频源决策：PREGENERATED（探测）→ STREAMING（真实文本）→ NO_NARRATION
 * - start / pause / resume / stop / replay / cancelStale / setVoiceType
 * - 请求代际（generation）单调递增，作废旧 slide 的迟到结果
 * - 产品级播放状态机（IDLE/PREPARING/BUFFERING/PLAYING/PAUSED/ENDED/ERROR/AUTOPLAY_BLOCKED）
 * - 诚实暂停能力：仅流式路径可暂停；预生成无真实暂停则绝不显示 PAUSED
 * - 3D 模型缺失时音频兜底（HTMLAudioElement/ObjectURL），模型失败不使课程不可用
 *
 * 明确不拥有（保持 M4 canonical）：
 * - next/previous slide、课程导航、lesson 进度
 *
 * 隐藏教师（HIDE_TEACHER）与静音（MUTE_AUDIO）是独立语义：
 * 本适配器只处理音频；教师可见性由调用方 UI 单独管理。
 *
 * 纯逻辑、依赖注入，可在 node/jest 下独立测试。
 */

/** 音频源类型（业务决策结果）。 */
export const SPEECH_SOURCE = {
  PREGENERATED_AUDIO: 'PREGENERATED_AUDIO',
  M8_STREAMING_TTS: 'M8_STREAMING_TTS',
  NO_NARRATION: 'NO_NARRATION',
};

/** 产品级播放状态（不向用户暴露 PCM/ReadableStream/queue 等技术词）。 */
export const SPEECH_STATE = {
  IDLE: 'IDLE',
  PREPARING: 'PREPARING',
  BUFFERING: 'BUFFERING',
  PLAYING: 'PLAYING',
  PAUSED: 'PAUSED',
  ENDED: 'ENDED',
  ERROR: 'ERROR',
  AUTOPLAY_BLOCKED: 'AUTOPLAY_BLOCKED',
};

/**
 * 纯函数：从 slide 描述做音频源决策。
 * 注意：远程 slide 不得仅凭"是远程"就推断有音频；
 * 有预生成音频的证据（segments 已探测/存在）才选 PREGENERATED。
 *
 * @param {Object} input
 * @param {boolean} [input.hasPregeneratedAudio=false] 预生成音频是否已确认存在
 * @param {boolean} [input.hasNarrationText=false] 是否存在可用旁白文本
 * @returns {string} SPEECH_SOURCE.*
 */
export function decideAudioSource({ hasPregeneratedAudio = false, hasNarrationText = false } = {}) {
  if (hasPregeneratedAudio) return SPEECH_SOURCE.PREGENERATED_AUDIO;
  if (hasNarrationText) return SPEECH_SOURCE.M8_STREAMING_TTS;
  return SPEECH_SOURCE.NO_NARRATION;
}

/**
 * 将 M8 流式播放内部状态映射为产品状态。
 * STREAMING_BUFFERING / PREPARING_SPEECH 等技术内部词在此归一。
 */
function mapStreamStateToProduct(state) {
  switch (state) {
    case 'PREPARING_SPEECH':
      return SPEECH_STATE.PREPARING;
    case 'STREAMING_BUFFERING':
      return SPEECH_STATE.BUFFERING;
    case 'PLAYING':
      return SPEECH_STATE.PLAYING;
    case 'PAUSED':
      return SPEECH_STATE.PAUSED;
    case 'ENDED':
      return SPEECH_STATE.ENDED;
    case 'STREAM_ERROR':
      return SPEECH_STATE.ERROR;
    case 'AUTOPLAY_BLOCKED':
      return SPEECH_STATE.AUTOPLAY_BLOCKED;
    default:
      return SPEECH_STATE.IDLE;
  }
}

/**
 * 从 slide 元数据中提取旁白文本（真实字段：title / html_content / htmlContent / content）。
 * HTML 安全归一为纯文本。
 */
export function extractNarrationText(slide = {}) {
  const raw =
    slide.narrationText ||
    slide.title ||
    slide.html_content ||
    slide.htmlContent ||
    slide.content ||
    '';
  if (!raw || typeof raw !== 'string') return '';
  const text = raw
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/<style[\s\S]*?<\/style>/gi, '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&quot;/g, '"')
    .replace(/\s+/g, ' ')
    .trim();
  return text;
}

/**
 * M8 讲课语音适配器。
 *
 * @param {Object} deps 依赖注入（便于独立测试）
 * @param {Function} [deps.getModel] 返回当前 VRM 模型（含 speak/stopSpeaking），可为 null
 * @param {Function} [deps.fetchSegments] 预生成音频段获取器（M4 现有 fetchSegments 契约）
 * @param {Function} [deps.streamSpeech] 流式 TTS 发起器（返回 {reader, headers}）
 * @param {Function} [deps.createStreamingPlayback] 创建流式播放器（StreamingPlayback 工厂）
 * @param {Function} [deps.createFallbackAudio] 无模型时音频兜底播放器工厂：
 *   (() => { element, play(), stop(), cleanup() }) —— 默认使用 HTMLAudioElement
 * @param {Function} [deps.onStateChange] 产品状态回调
 * @param {Function} [deps.onSubtitle] 字幕回调（可选）
 * @param {string} [deps.voiceType] 流式 TTS 音色（可选，后续可经 setVoiceType 更新）
 */
export class LectureSpeechAdapter {
  constructor({
    getModel = () => null,
    fetchSegments = null,
    streamSpeech = null,
    createStreamingPlayback = null,
    createFallbackAudio = null,
    onStateChange = () => {},
    onSubtitle = () => {},
    voiceType = 'default',
  } = {}) {
    this.getModel = getModel;
    this.fetchSegments = fetchSegments;
    this.streamSpeech = streamSpeech;
    this.createStreamingPlayback = createStreamingPlayback;
    this.createFallbackAudio = createFallbackAudio;
    this.onStateChange = onStateChange;
    this.onSubtitle = onSubtitle;
    this.voiceType = voiceType;

    // 当前 slide 身份（仅记录，不拥有导航）
    this.slide = null;

    // 请求代际：单调递增；所有异步结果落地前校验代际
    this.generation = 0;

    // 播放器引用
    this.streamingPlayback = null;
    this.fallbackAudio = null;
    this.currentSource = null; // PREGENERATED | STREAMING | FALLBACK_AUDIO | NONE

    this.state = SPEECH_STATE.IDLE;
    this.error = null;
  }

  setState(state, info) {
    if (this.state === state) return;
    this.state = state;
    try {
      this.onStateChange(state, info);
    } catch (_) {
      // UI 回调失败不影响语音
    }
  }

  /** 绑定当前 slide 并立即作废旧代际、停止旧播放（不自动开始旁白）。 */
  bindSlide(slide) {
    this.generation += 1;
    this.cancelActivePlayback();
    this.slide = slide || null;
    this.error = null;
    this.currentSource = null;
    this.setState(SPEECH_STATE.IDLE);
  }

  /**
   * 更新流式音色（换形象时调用）。
   * 不 bindSlide、不递增代际、不停止当前音频、不重启旁白。
   * 下一次流式 start/replay 使用新音色；预生成音频不受影响。
   */
  setVoiceType(voiceType) {
    this.voiceType = voiceType || 'default';
  }

  /**
   * 开始当前 slide 的讲解（用户触发语义）。
   * 真实策略：
   * 1. 尝试解析预生成 segments（若 slide 未确认有音频则探测）
   * 2. 有效 → PREGENERATED
   * 3. 无 segments 但 slide 有可用文本 → STREAMING
   * 4. 否则 → NO_NARRATION
   */
  async start() {
    const slide = this.slide;
    if (!slide) {
      this.setState(SPEECH_STATE.ERROR, { message: 'no slide bound' });
      return;
    }
    const gen = ++this.generation; // 每次 start 也是新的代际

    // ── 步骤 1：探测预生成音频（不臆断"远程=有音频"） ──
    this.setState(SPEECH_STATE.PREPARING);
    let hasPregenerated = Boolean(slide.hasPregeneratedAudio);
    if (!hasPregenerated && typeof this.fetchSegments === 'function' && slide.courseId) {
      try {
        const segments = await this.fetchSegments(slide.courseId, slide.pageNumber);
        if (gen !== this.generation) return; // 探测期间翻页：作废
        if (Array.isArray(segments) && segments.length > 0) hasPregenerated = true;
      } catch (_) {
        // 探测失败视为无预生成音频，继续回退
      }
    }

    // ── 步骤 2：文本（真实 slide 字段） ──
    const narrationText = extractNarrationText(slide);

    const source = decideAudioSource({ hasPregeneratedAudio: hasPregenerated, hasNarrationText: Boolean(narrationText) });
    if (source === SPEECH_SOURCE.NO_NARRATION) {
      this.setState(SPEECH_STATE.ENDED);
      return;
    }

    try {
      if (source === SPEECH_SOURCE.PREGENERATED_AUDIO) {
        await this.playPregenerated(gen, slide);
      } else {
        await this.playStreaming(gen, slide, narrationText);
      }
    } catch (error) {
      if (gen !== this.generation) return; // 已被新代际作废
      this.error = error;
      this.setState(SPEECH_STATE.ERROR, { message: error?.message || String(error) });
    }
  }

  /** 预生成路径：逐段播放；3D 模型缺失时走音频兜底。 */
  async playPregenerated(gen, slide) {
    let segments = slide.pregeneratedSegments;
    if (!Array.isArray(segments) && typeof this.fetchSegments === 'function') {
      segments = await this.fetchSegments(slide.courseId, slide.pageNumber);
    }
    if (gen !== this.generation) return; // 旧代际：丢弃
    if (!Array.isArray(segments) || segments.length === 0) {
      throw new Error('未找到该页面的预生成音频');
    }
    this.currentSource = SPEECH_SOURCE.PREGENERATED_AUDIO;
    this.setState(SPEECH_STATE.BUFFERING);
    for (let i = 0; i < segments.length; i += 1) {
      if (gen !== this.generation) return; // 翻页/停止后立即中断
      const seg = segments[i];
      const text = String(seg?.textContent || seg?.text || '');
      try {
        this.onSubtitle(text);
      } catch (_) {}
      const blob = this.blobFromSegment(seg);
      const arrayBuf = await blob.arrayBuffer();
      if (gen !== this.generation) return;
      this.setState(SPEECH_STATE.PLAYING);
      const model = this.getModel();
      if (model?.speak) {
        await model.speak(arrayBuf, { expression: 'neutral', talk: { message: text } });
      } else if (typeof this.createFallbackAudio === 'function' || this.createFallbackAudio == null) {
        // 3D 模型缺失 → 音频兜底（不发 lip-sync，课程仍可用）
        const blocked = await this.playFallbackAudio(arrayBuf, gen);
        if (blocked) return; // autoplay 被浏览器阻塞：保持 AUTOPLAY_BLOCKED，不覆盖为 ENDED
      } else {
        throw new Error('3D 模型未就绪，且无音频兜底');
      }
    }
    if (gen !== this.generation) return;
    this.currentSource = null;
    this.setState(SPEECH_STATE.ENDED);
  }

  /** 无模型音频兜底：HTMLAudioElement/ObjectURL（依赖注入可替换）。返回是否被 autoplay 阻塞。 */
  async playFallbackAudio(arrayBuf, gen) {
    this.stopFallbackAudio();
    this.currentSource = 'FALLBACK_AUDIO';
    if (this.createFallbackAudio) {
      const fallback = this.createFallbackAudio();
      if (!fallback || typeof fallback.play !== 'function') {
        throw new Error('音频兜底播放器无效');
      }
      this.fallbackAudio = fallback;
      try {
        await fallback.play(arrayBuf, gen);
      } catch (error) {
        // 浏览器拒绝自动播放（autoplay 策略）≠ TTS 服务失败
        if (error?.name === 'NotAllowedError' || error?.name === 'AbortError') {
          this.setState(SPEECH_STATE.AUTOPLAY_BLOCKED, { message: 'autoplay blocked' });
          return true;
        }
        throw error;
      } finally {
        if (gen === this.generation) this.stopFallbackAudio();
      }
      return false;
    }
    // 默认实现：HTMLAudioElement + ObjectURL（浏览器环境）
    if (typeof document === 'undefined' || typeof URL === 'undefined') {
      throw new Error('当前环境无 HTMLAudioElement 音频兜底');
    }
    const blob = new Blob([arrayBuf], { type: 'audio/wav' });
    const url = URL.createObjectURL(blob);
    const audio = new Audio(url);
    let settled = false;
    let settle = null;
    this.fallbackAudio = {
      element: audio,
      url,
      // 契约：play() 的 promise 在媒体触发 ended 时 resolve，在 error 时 reject，
      // 在 stop() 中断时同样 settle（视为被安全中断，避免悬挂 await）。
      // 绝不在 audio.play() 开始时就 resolve —— 否则段 2 会在段 1 未结束时就开始。
      play: () =>
        new Promise((resolve, reject) => {
          settle = () => { if (!settled) { settled = true; resolve(); } };
          const fail = (err) => { if (!settled) { settled = true; reject(err); } };
          audio.onended = () => settle();
          audio.onerror = () => fail(new Error('fallback audio error'));
          audio.play().catch((err) => fail(err));
        }),
      stop: () => {
        try { audio.pause(); } catch (_) {}
        try { URL.revokeObjectURL(url); } catch (_) {}
        if (typeof settle === 'function') settle(); // 中断即结束，防悬挂
      },
      cleanup: () => {
        try { URL.revokeObjectURL(url); } catch (_) {}
      },
    };
    try {
      await this.fallbackAudio.play();
    } catch (error) {
      if (error?.name === 'NotAllowedError' || error?.name === 'AbortError') {
        this.setState(SPEECH_STATE.AUTOPLAY_BLOCKED, { message: 'autoplay blocked' });
        return true;
      }
      throw error;
    } finally {
      if (gen === this.generation) this.stopFallbackAudio();
    }
    return false;
  }

  stopFallbackAudio() {
    if (this.fallbackAudio) {
      try { this.fallbackAudio.stop?.(); } catch (_) {}
      try { this.fallbackAudio.cleanup?.(); } catch (_) {}
      this.fallbackAudio = null;
    }
  }

  /** 将 M4 音频段（base64 audioData）转为 Blob。 */
  blobFromSegment(seg) {
    if (seg?.audioData) {
      const binary = atob(seg.audioData);
      const bytes = new Uint8Array(binary.length);
      for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
      const mime = seg.audioFormat === 'mp3' ? 'audio/mpeg' : seg.audioFormat === 'ogg' ? 'audio/ogg' : 'audio/wav';
      return new Blob([bytes], { type: mime });
    }
    throw new Error(`片段 ${seg?.segmentIndex ?? '?'} 缺少音频数据`);
  }

  /** 流式路径：streamSpeech → StreamingPlayback（Phase A 链路 + 代际防护）。 */
  async playStreaming(gen, slide, narrationText) {
    if (!this.streamSpeech || !this.createStreamingPlayback) {
      throw new Error('流式 TTS 路径依赖缺失（streamSpeech/createStreamingPlayback）');
    }
    this.currentSource = SPEECH_SOURCE.M8_STREAMING_TTS;
    const { reader, headers } = await this.streamSpeech({
      courseId: slide.courseId,
      text: narrationText,
      voiceType: this.voiceType,
    });
    if (gen !== this.generation) {
      try { reader.cancel(); } catch (_) {}
      return;
    }
    const playback = this.createStreamingPlayback({
      sampleRate: headers?.sampleRate,
      channels: headers?.channels,
      byteOrder: headers?.byteOrder,
      onStateChange: (state) => this.setState(mapStreamStateToProduct(state)),
      onLipSyncFrame: (weights, volume, active) => {
        try {
          const model = this.getModel();
          const emote = model?.emoteController;
          if (!emote) return;
          if (weights) emote.lipSyncWeights(weights);
          else if (active || volume > 0) emote.lipSync('aa', volume);
        } catch (_) {}
      },
    });
    this.streamingPlayback = playback;
    await playback.playFromReader(reader);
    if (gen === this.generation && playback.state === 'ENDED') {
      this.currentSource = null;
      this.setState(SPEECH_STATE.ENDED);
    }
  }

  /**
   * 真实暂停能力：仅流式路径（StreamingPlayback 原生 suspend）可暂停。
   * 预生成/兜底路径无真实暂停机制 → 绝不得显示 PAUSED。
   */
  canPause() {
    return this.currentSource === SPEECH_SOURCE.M8_STREAMING_TTS && Boolean(this.streamingPlayback);
  }

  pause() {
    if (!this.canPause()) return; // 预生成/兜底：诚实 no-op，不伪造 PAUSED
    this.streamingPlayback.pause();
  }

  async resume() {
    if (this.streamingPlayback && this.state === SPEECH_STATE.PAUSED) {
      await this.streamingPlayback.resume();
    }
  }

  stop() {
    this.generation += 1;
    this.cancelActivePlayback();
    this.error = null;
    this.currentSource = null;
    this.setState(SPEECH_STATE.ENDED);
  }

  /** 重播当前 slide（先停后按策略重来）。 */
  async replay() {
    this.generation += 1;
    this.cancelActivePlayback();
    this.currentSource = null;
    this.setState(SPEECH_STATE.IDLE);
    await this.start();
  }

  cancelStale() {
    this.generation += 1;
    this.cancelActivePlayback();
  }

  cancelActivePlayback() {
    if (this.streamingPlayback) {
      try { this.streamingPlayback.stop(); } catch (_) {}
      this.streamingPlayback = null;
    }
    this.stopFallbackAudio();
    try {
      const model = this.getModel();
      if (model?.stopSpeaking) model.stopSpeaking();
    } catch (_) {}
  }

  dispose() {
    this.generation += 1;
    this.cancelActivePlayback();
    this.slide = null;
    this.currentSource = null;
    this.setState(SPEECH_STATE.IDLE);
  }

  getState() {
    return this.state;
  }

  getError() {
    return this.error;
  }

  getGeneration() {
    return this.generation;
  }

  getSource() {
    return this.currentSource;
  }

  setTeacherVisible(_visible) {
    // HIDE_TEACHER != MUTE_AUDIO：纯视觉语义，不做任何音频操作。
    return true;
  }
}
