/**
 * M8 流式 PCM 播放模块（BOUNDED SHORT-STREAM PRAGMATIC PLAYBACK）。
 *
 * 负责：ReadableStream 分块输入、PCM 字节 carry 处理、Int16→Float32 解码、
 * 有界调度队列、AudioContext 生命周期、AudioBuffer 定时调度、暂停/恢复/停止、
 * autoplay 阻塞处理、流错误、analyser 连接（复用现有 LipSync 类驱动口型）。
 *
 * 不包含网络 fetch 逻辑（由 virtualTeacherService 负责）。
 *
 * 说明：Phase A 采用「有界短流务实播放」——通过 AudioContext.currentTime +
 * 已调度时长维护调度游标，小阈值缓冲后开始播放。AudioWorklet 仅在出现
 * underrun/间隙问题时作为未来升级方案，不在 Phase A 实现。
 */
import { LipSync } from '../lipSync/lipSync.js';

export const STREAM_STATE = {
  PREPARING_SPEECH: 'PREPARING_SPEECH',
  STREAMING_BUFFERING: 'STREAMING_BUFFERING',
  PLAYING: 'PLAYING',
  PAUSED: 'PAUSED',
  ENDED: 'ENDED',
  STREAM_ERROR: 'STREAM_ERROR',
  AUTOPLAY_BLOCKED: 'AUTOPLAY_BLOCKED',
};

/** 流式失败后的动作决策（纯业务规则，供页面与独立测试共享）。 */
export const STREAM_FAILURE_ACTION = {
  FALLBACK_BLOCKING: 'FALLBACK_BLOCKING', // 零 PCM 前的失败：允许恰好一次阻塞回退
  STREAM_ERROR: 'STREAM_ERROR',           // 首个 PCM 已调度后的失败：不回退，置错误态
  ENDED: 'ENDED',                         // 用户主动取消（AbortError）：视为正常结束
};

/**
 * 纯函数：根据「是否已消费首个 PCM」与「是否用户取消」决定流式失败动作。
 *
 * 业务规则：
 * - 零 PCM 消费 + 普通失败 → FALLBACK_BLOCKING（允许阻塞回退，恰好一次）
 * - 首个 PCM 已调度 + 普通失败 → STREAM_ERROR（不得回退，避免重复讲解/双 TTS/音频重叠）
 * - AbortError（用户取消）→ ENDED（不算失败）
 */
export function decideStreamingFailureAction({ hasConsumedPcm = false, aborted = false } = {}) {
  if (aborted) return STREAM_FAILURE_ACTION.ENDED;
  if (hasConsumedPcm) return STREAM_FAILURE_ACTION.STREAM_ERROR;
  return STREAM_FAILURE_ACTION.FALLBACK_BLOCKING;
}

const DEFAULT_SAMPLE_RATE = 16000;
const DEFAULT_CHANNELS = 1;
const BYTES_PER_SAMPLE = 2; // 16-bit signed

/** 开始播放前的最小缓冲毫秒数。 */
const BUFFER_THRESHOLD_MS = 200;

/** 最大已调度播放时长（毫秒），防止无限超前调度。 */
const MAX_SCHEDULED_AHEAD_MS = 2000;

/** 单块 AudioBuffer 的最大采样数（约 200ms @16k）。 */
const MAX_BUFFER_SAMPLES = 3200;

export class StreamingPlayback {
  /**
   * @param {Object} options
   * @param {number} [options.sampleRate=16000]
   * @param {number} [options.channels=1]
   * @param {'little-endian'|'big-endian'} [options.byteOrder='little-endian']
   * @param {(state: string, info?: Object) => void} [options.onStateChange]
   * @param {(weights: Object|undefined, volume: number, active: boolean) => void} [options.onLipSyncFrame]
   */
  constructor({
    sampleRate = DEFAULT_SAMPLE_RATE,
    channels = DEFAULT_CHANNELS,
    byteOrder = 'little-endian',
    onStateChange = () => {},
    onLipSyncFrame = null,
  } = {}) {
    this.sampleRate = sampleRate;
    this.channels = channels;
    this.byteOrder = byteOrder;
    this.onStateChange = onStateChange;
    this.onLipSyncFrame = onLipSyncFrame;

    this.audioContext = null;
    this.lipSync = null;
    this.pcmQueue = [];
    this.carryByte = null; // 跨 chunk 的半字节 carry
    this.schedulingCursor = 0; // 已调度到的 AudioContext.currentTime 偏移
    this.currentSource = null;
    this.sources = new Set();
    this.started = false;
    this.state = STREAM_STATE.PREPARING_SPEECH;
    this.ended = false;
    this.error = null;
    this.reader = null;
    this.paused = false;
    this._lipSyncFrameId = null;
    this._lastLipSyncVolume = 0;
    this.firstPcmScheduled = false; // 首个 PCM 已调度（fallback 判定边界）
  }

  setState(state, info) {
    if (this.state === state) return;
    this.state = state;
    try {
      this.onStateChange(state, info);
    } catch (_) {
      // UI 回调失败不影响播放
    }
  }

  /**
   * 接入 fetch 返回的 ReadableStreamDefaultReader，开始消费分块。
   * @param {ReadableStreamDefaultReader} reader
   */
  async playFromReader(reader) {
    this.reader = reader;
    this.ensureAudioContext();
    if (this.audioContext.state === 'suspended') {
      this.setState(STREAM_STATE.AUTOPLAY_BLOCKED);
      // 等待用户手势 resume（见 resumeFromUserGesture）
    }
    this.setState(STREAM_STATE.STREAMING_BUFFERING);
    this.started = true;
    this.startLipSyncLoop();
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        if (!value || value.byteLength === 0) continue;
        const samples = this.decodePcmChunk(new Uint8Array(value));
        if (samples.length > 0) {
          this.enqueueSamples(samples);
        }
      }
      this.flushCarry();
      // 流结束：强制调度剩余 PCM（忽略首播缓冲阈值，避免残留数据永远不被播放）
      this.scheduleAvailable(true);
      if (this.pcmQueue.length === 0 && !this.hasScheduledAudio()) {
        this.stopLipSyncLoop();
        this.clearLipSyncActiveSource();
        this.setState(STREAM_STATE.ENDED);
      } else {
        // 已调度音频结束后进入 ENDED
        this.ended = true;
        this.waitForScheduledCompletion();
      }
    } catch (error) {
      this.error = error;
      this.stopLipSyncLoop();
      this.clearLipSyncActiveSource();
      this.stopScheduledAudio();
      this.setState(STREAM_STATE.STREAM_ERROR, { message: error?.message || String(error) });
    } finally {
      this.reader = null;
    }
  }

  /** 停止全部已调度源并清空队列（错误/停止时调用，避免残留/重叠音频）。 */
  stopScheduledAudio() {
    for (const source of this.sources) {
      try {
        source.stop();
      } catch (_) {
        // 已停止
      }
    }
    this.sources.clear();
    this.currentSource = null;
    this.pcmQueue.length = 0;
    this.carryByte = null;
  }

  /** 将 PCM 字节块解码为 Float32 采样数组（处理跨块半字节）。 */
  decodePcmChunk(bytes) {
    const out = [];
    let offset = 0;
    if (this.carryByte !== null) {
      if (bytes.byteLength < 1) {
        // 无第二个字节，保留 carry
        return out;
      }
      const second = bytes[0];
      const sample = this.combineSample(this.carryByte, second);
      out.push(sample);
      this.carryByte = null;
      offset = 1;
    }
    const rem = bytes.byteLength - offset;
    const sampleCount = Math.floor(rem / BYTES_PER_SAMPLE);
    for (let i = 0; i < sampleCount; i++) {
      const lo = bytes[offset + i * 2];
      const hi = bytes[offset + i * 2 + 1];
      out.push(this.combineSample(lo, hi));
    }
    const leftoverStart = offset + sampleCount * 2;
    if (leftoverStart < bytes.byteLength) {
      this.carryByte = bytes[leftoverStart];
    }
    return out;
  }

  /** 依据字节序合成 Int16 并归一化为 Float32。 */
  combineSample(byteA, byteB) {
    let int16;
    if (this.byteOrder === 'big-endian') {
      int16 = (byteA << 8) | byteB;
    } else {
      // little-endian
      int16 = byteA | (byteB << 8);
    }
    // 有符号 16 位 → Float32 [-1, 1)
    return int16 >= 0x8000 ? (int16 - 0x10000) / 32768 : int16 / 32768;
  }

  /** 将残留的单个 carry 字节丢弃（音频流以 16 位为单元，多余单字节为畸形数据）。 */
  flushCarry() {
    this.carryByte = null;
  }

  enqueueSamples(samples) {
    this.pcmQueue.push(...samples);
    // 有界：只保留最近约 MAX_SCHEDULED_AHEAD_MS 的采样，防止无限增长
    const maxQueued = Math.ceil((MAX_SCHEDULED_AHEAD_MS / 1000) * this.sampleRate) * this.channels + MAX_BUFFER_SAMPLES;
    if (this.pcmQueue.length > maxQueued) {
      const drop = this.pcmQueue.length - maxQueued;
      this.pcmQueue.splice(0, drop);
    }
    this.scheduleAvailable();
  }

  /** 将队列中可调度的采样调度到 AudioContext。 */
  scheduleAvailable(force = false) {
    if (!this.audioContext || this.audioContext.state === 'suspended') return;
    if (this.paused) return;

    // 首次：等待缓冲阈值（流结束时 force=true 跳过阈值）
    const bufferedMs = (this.pcmQueue.length / this.channels / this.sampleRate) * 1000;
    if (!force && !this.currentSource && bufferedMs < BUFFER_THRESHOLD_MS) {
      this.setState(STREAM_STATE.STREAMING_BUFFERING);
      return;
    }

    const now = this.audioContext.currentTime;
    if (this.schedulingCursor === 0) {
      this.schedulingCursor = now + 0.05; // 轻微前瞻
    }
    const maxCursor = now + MAX_SCHEDULED_AHEAD_MS / 1000;
    let cursor = this.schedulingCursor;

    const maxFrames = MAX_BUFFER_SAMPLES;
    while (this.pcmQueue.length >= this.channels && cursor < maxCursor) {
      const frames = Math.min(Math.floor(this.pcmQueue.length / this.channels), maxFrames);
      const buffer = this.audioContext.createBuffer(
        this.channels,
        frames,
        this.sampleRate,
      );
      for (let ch = 0; ch < this.channels; ch++) {
        const data = buffer.getChannelData(ch);
        for (let i = 0; i < frames; i++) {
          data[i] = this.pcmQueue[ch + i * this.channels];
        }
      }
      // 弹出已用采样
      this.pcmQueue.splice(0, frames * this.channels);

      const source = this.audioContext.createBufferSource();
      source.buffer = buffer;
      // 输出到扬声器 + 送入 lip-sync analyser
      source.connect(this.audioContext.destination);
      if (this.lipSync?.analyser) {
        source.connect(this.lipSync.analyser);
      }
      source.start(cursor);
      this.sources.add(source);
      this.firstPcmScheduled = true; // 首个 PCM 已实际调度到播放管线
      source.addEventListener('ended', () => this.sources.delete(source), { once: true });
      cursor += buffer.duration;
    }
    this.schedulingCursor = cursor;

    if (this.sources.size > 0) {
      this.currentSource = this.sources.values().next().value;
      // 将真实的当前音频源挂到 LipSync.currentSource：
      // 这是 LipSync.update() 判定 active 的正式门控（currentSource / mic / media element）。
      // 使用真实调度源节点而非假对象，使流式音频真正驱动口型分析。
      if (this.lipSync) {
        this.lipSync.currentSource = this.currentSource;
      }
      this.setState(STREAM_STATE.PLAYING);
    } else if (this.ended && this.pcmQueue.length === 0 && this.sources.size === 0) {
      this.clearLipSyncActiveSource();
      this.setState(STREAM_STATE.ENDED);
    } else {
      this.setState(STREAM_STATE.STREAMING_BUFFERING);
    }
  }

  /** 播放停止/暂停/结束/出错时清空 lip-sync 活跃源（口型分析随之进入 inactive）。 */
  clearLipSyncActiveSource() {
    if (this.lipSync) {
      this.lipSync.currentSource = null;
    }
  }

  /** 是否已消费/调度首个 PCM（判定是否允许阻塞回退：仅首个 PCM 前允许）。 */
  hasConsumedPcm() {
    return this.firstPcmScheduled;
  }

  hasScheduledAudio() {
    return this.sources.size > 0 || this.pcmQueue.length > 0;
  }

  /** 已调度音频自然播完后置 ENDED。 */
  waitForScheduledCompletion() {
    if (!this.audioContext) {
      this.stopLipSyncLoop();
      this.setState(STREAM_STATE.ENDED);
      return;
    }
    const check = () => {
      if (this.sources.size === 0 && this.pcmQueue.length === 0) {
        this.stopLipSyncLoop();
        this.clearLipSyncActiveSource();
        this.setState(STREAM_STATE.ENDED);
        return;
      }
      requestAnimationFrame(check);
    };
    requestAnimationFrame(check);
  }

  ensureAudioContext() {
    if (!this.audioContext) {
      // node/jest 环境兼容：globalThis.AudioContext（测试注入）
      const root = (typeof globalThis !== 'undefined' ? globalThis : (typeof window !== 'undefined' ? window : {}));
      const Ctx = root.AudioContext || root.webkitAudioContext;
      if (!Ctx) {
        throw new Error('当前环境不支持 AudioContext');
      }
      this.audioContext = new Ctx();
      // 复用现有 LipSync 类做分析驱动口型（与 model.js 相同的分析路径）
      this.lipSync = new LipSync(this.audioContext);
    }
  }

  /** rAF 循环：把 analyser 数据转发为口型权重（lip-sync 失败不影响音频）。 */
  startLipSyncLoop() {
    this.stopLipSyncLoop();
    const tick = () => {
      if (!this.started || this.state === STREAM_STATE.ENDED || this.state === STREAM_STATE.STREAM_ERROR) {
        return;
      }
      try {
        if (this.lipSync) {
          const { volume, weights, active } = this.lipSync.update();
          this._lastLipSyncVolume = volume;
          if (this.onLipSyncFrame) {
            this.onLipSyncFrame(weights, volume, active || this.sources.size > 0);
          }
        }
      } catch (_) {
        // lip-sync 异常不得终止 TTS 播放
      }
      this._lipSyncFrameId = requestAnimationFrame(tick);
    };
    this._lipSyncFrameId = requestAnimationFrame(tick);
  }

  stopLipSyncLoop() {
    if (this._lipSyncFrameId != null) {
      cancelAnimationFrame(this._lipSyncFrameId);
      this._lipSyncFrameId = null;
    }
  }

  /** 用户手势后恢复 AudioContext（autoplay 处理）。 */
  async resumeFromUserGesture() {
    this.ensureAudioContext();
    try {
      await this.audioContext.resume();
    } catch (error) {
      this.error = error;
      this.stopLipSyncLoop();
      this.setState(STREAM_STATE.STREAM_ERROR, { message: error?.message || String(error) });
      return;
    }
    if (this.state === STREAM_STATE.AUTOPLAY_BLOCKED) {
      this.setState(STREAM_STATE.STREAMING_BUFFERING);
      this.scheduleAvailable();
    }
  }

  pause() {
    if (!this.audioContext) return;
    if (this.audioContext.state !== 'suspended') {
      this.audioContext.suspend();
    }
    this.paused = true;
    this.clearLipSyncActiveSource();
    this.setState(STREAM_STATE.PAUSED);
  }

  async resume() {
    if (!this.audioContext) return;
    this.paused = false;
    try {
      await this.audioContext.resume();
    } catch (error) {
      this.error = error;
      this.stopLipSyncLoop();
      this.setState(STREAM_STATE.STREAM_ERROR, { message: error?.message || String(error) });
      return;
    }
    this.scheduleAvailable();
    if (this.sources.size > 0) {
      this.setState(STREAM_STATE.PLAYING);
    } else {
      this.setState(STREAM_STATE.STREAMING_BUFFERING);
    }
  }

  stop() {
    this.ended = true;
    this.paused = false;
    this.stopLipSyncLoop();
    this.clearLipSyncActiveSource();
    this.stopScheduledAudio();
    if (this.reader) {
      try {
        this.reader.cancel();
      } catch (_) {
        // ignore
      }
      this.reader = null;
    }
    if (this.audioContext && this.audioContext.state !== 'closed') {
      this.audioContext.close().catch(() => {});
      this.audioContext = null;
      this.lipSync = null;
    }
    this.setState(STREAM_STATE.ENDED);
  }

  /** 暴露 analyser（供外部读取，可选）。 */
  getAnalyser() {
    return this.lipSync?.analyser || null;
  }

  getState() {
    return this.state;
  }

  getError() {
    return this.error;
  }
}
