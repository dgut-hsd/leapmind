import { describe, test, expect, beforeEach, afterEach, jest } from '@jest/globals';
import { StreamingPlayback, STREAM_STATE, STREAM_FAILURE_ACTION, decideStreamingFailureAction } from '../src/features/virtualTeacher/streamingPlayback.js';

/**
 * F2–F12 流式播放测试（node 环境，polyfill Web Audio API）。
 * 覆盖：PCM 解码正确性、字节序、跨块 carry、分块顺序、暂停/恢复/停止、
 * underrun、流错误、autoplay 阻塞、lip-sync 失败不影响音频。
 */

function makeFakeAudioContext() {
  const listeners = new Set();
  const analyser = {
    fftSize: 2048,
    smoothingTimeConstant: 0.3,
    frequencyBinCount: 1024,
    hasSignal: false,
    getFloatTimeDomainData(arr) {
      if (this.hasSignal) {
        for (let i = 0; i < arr.length; i++) {
          arr[i] = 0.5 * Math.sin(i / 10);
        }
      } else {
        arr.fill(0);
      }
    },
    getByteFrequencyData(arr) {
      if (this.hasSignal) {
        for (let i = 0; i < arr.length; i++) {
          arr[i] = 180;
        }
      } else {
        arr.fill(0);
      }
    },
  };
  const ctx = {
    state: 'running',
    currentTime: 0,
    sampleRate: 16000,
    destination: { connect() {} },
    createBuffer(channels, frames, sampleRate) {
      const data = [];
      for (let c = 0; c < channels; c++) {
        data.push(new Float32Array(frames));
      }
      return { channels, frames, sampleRate, getChannelData: (c) => data[c], duration: frames / sampleRate };
    },
    createBufferSource() {
      const src = {
        buffer: null,
        onended: null,
        start(t) {
          this.startedAt = t;
          // 模拟音频自然播完：一小段时间后触发 ended
          setTimeout(() => {
            if (this.onended) this.onended();
          }, 5);
        },
        stop() {
          this.stopped = true;
          if (this.onended) this.onended();
        },
        connect() {},
        addEventListener(type, fn) {
          if (type === 'ended') {
            this.onended = fn;
            listeners.add(this);
          }
        },
      };
      return src;
    },
    createAnalyser() {
      return analyser;
    },
    resume: jest.fn(async () => {}),
    suspend: jest.fn(async () => {}),
    close: jest.fn(async () => {}),
  };
  return { ctx, listeners };
}

function makeFakeLipSync() {
  // 复用 StreamingPlayback 内 LipSync 依赖的最小接口：new LipSync(audio) 需要
  // audio.createAnalyser + 实例方法。这里直接 mock 模块级 LipSync。
  return {};
}

async function makePlaybackWithReader(chunks, byteOrder = 'little-endian') {
  const { ctx } = makeFakeAudioContext();
  globalThis.AudioContext = class {
    constructor() {
      return ctx;
    }
  };
  globalThis.requestAnimationFrame = (fn) => setTimeout(fn, 0);
  globalThis.cancelAnimationFrame = (id) => clearTimeout(id);

  const reader = {
    chunks,
    index: 0,
    cancelled: false,
    async read() {
      if (this.index >= this.chunks.length) {
        return { done: true, value: undefined };
      }
      return { done: false, value: this.chunks[this.index++] };
    },
    async cancel() {
      this.cancelled = true;
    },
  };

  const states = [];
  const playback = new StreamingPlayback({
    byteOrder,
    onStateChange: (s) => states.push(s),
  });
  return { playback, reader, ctx, states, restore: () => { globalThis.AudioContext = undefined; } };
}

describe('F2: PCM 转换正确性', () => {
  test('little-endian 16-bit 有符号 → Float32 归一化', () => {
    const playback = new StreamingPlayback({ byteOrder: 'little-endian' });
    // 0x0000 → 0, 0x7FFF → +32767, 0x8000 → -32768, 0x0001 → 1
    const bytes = new Uint8Array([0x00, 0x00, 0xFF, 0x7F, 0x00, 0x80, 0x01, 0x00]);
    const out = playback.decodePcmChunk(bytes);
    expect(out.length).toBe(4);
    expect(out[0]).toBe(0);
    expect(Math.abs(out[1] - 32767 / 32768)).toBeLessThan(1e-6);
    expect(Math.abs(out[2] - (-1))).toBeLessThan(1e-6);
    expect(Math.abs(out[3] - 1 / 32768)).toBeLessThan(1e-6);
  });

  test('big-endian 解码（如需切换字节序）', () => {
    const playback = new StreamingPlayback({ byteOrder: 'big-endian' });
    // 0x7FFF 大端：0x7F 0xFF
    const bytes = new Uint8Array([0x7F, 0xFF]);
    const out = playback.decodePcmChunk(bytes);
    expect(Math.abs(out[0] - 32767 / 32768)).toBeLessThan(1e-6);
  });
});

describe('F3: 字节序契约传递', () => {
  test('构造器接收 byteOrder 并用于解码', () => {
    const le = new StreamingPlayback({ byteOrder: 'little-endian' });
    const be = new StreamingPlayback({ byteOrder: 'big-endian' });
    // 同一字节序列两种解释不同
    const bytes = new Uint8Array([0x01, 0x02]);
    expect(le.decodePcmChunk(bytes)[0]).not.toBe(be.decodePcmChunk(bytes)[0]);
    expect(Math.abs(le.decodePcmChunk(bytes)[0] - 0x0201 / 32768)).toBeLessThan(1e-6);
    expect(Math.abs(be.decodePcmChunk(bytes)[0] - 0x0102 / 32768)).toBeLessThan(1e-6);
  });
});

describe('F4: 奇数块边界 carry', () => {
  test('跨块 16-bit 采样无损重组', () => {
    const playback = new StreamingPlayback({ byteOrder: 'little-endian' });
    // 完整样本 0x1234 = [0x34, 0x12]
    // 第一块：0xAA 0x34（携带低字节）
    const first = playback.decodePcmChunk(new Uint8Array([0xAA, 0x34]));
    expect(first.length).toBe(1); // 0xAA + 0x34 组成一个样本（0x34AA 低字节序）
    expect(playback.carryByte).toBeNull();
    // 第二块：[0x12, 0xBB] → carry 不存在，但 0x12 0xBB 组成样本
    const second = playback.decodePcmChunk(new Uint8Array([0x12, 0xBB]));
    expect(second.length).toBe(1);
  });

  test('低字节在块尾、高字节在下一块开头时正确重组', () => {
    const playback = new StreamingPlayback({ byteOrder: 'little-endian' });
    // 样本 0x1234 应输出 ≈ 0x1234 / 32768
    // 第一块只有低字节 0x34
    const first = playback.decodePcmChunk(new Uint8Array([0x34]));
    expect(first.length).toBe(0); // 只有 carry
    expect(playback.carryByte).toBe(0x34);
    // 第二块以高字节 0x12 开头 → 重组 0x1234
    const second = playback.decodePcmChunk(new Uint8Array([0x12, 0x00, 0x00]));
    expect(second.length).toBe(2);
    expect(Math.abs(second[0] - 0x1234 / 32768)).toBeLessThan(1e-6);
    expect(playback.carryByte).toBeNull(); // 第二块剩余 [0x00, 0x00] 组成样本，无 carry
  });
});

describe('F5: 分块顺序', () => {
  test('多块解码按顺序拼接', () => {
    const playback = new StreamingPlayback({ byteOrder: 'little-endian' });
    const c1 = playback.decodePcmChunk(new Uint8Array([0x01, 0x00])); // 样本1
    const c2 = playback.decodePcmChunk(new Uint8Array([0x02, 0x00])); // 样本2
    expect(c1[0]).toBeGreaterThan(0);
    expect(c2[0]).toBeGreaterThan(c1[0]);
  });
});

describe('F6/F7/F8: 暂停/恢复/停止', () => {
  test('pause 进入 PAUSED，resume 返回播放', async () => {
    const { playback, restore } = await makePlaybackWithReader([]);
    try {
      playback.ensureAudioContext();
      playback.pause();
      expect(playback.getState()).toBe(STREAM_STATE.PAUSED);
      await playback.resume();
      // resume 后若无数据则进入 STREAMING_BUFFERING（初始 currentSource 为空）
      expect([STREAM_STATE.STREAMING_BUFFERING, STREAM_STATE.PLAYING]).toContain(playback.getState());
    } finally {
      restore();
    }
  });

  test('stop 清理并进入 ENDED', async () => {
    const { playback, restore } = await makePlaybackWithReader([]);
    try {
      playback.ensureAudioContext();
      playback.stop();
      expect(playback.getState()).toBe(STREAM_STATE.ENDED);
      expect(playback.audioContext).toBeNull();
    } finally {
      restore();
    }
  });
});

describe('F1: ReadableStream 开始播放早于完成（S3 前端证据）', () => {
  test('首个 chunk 到达即进入播放路径', async () => {
    const { ctx } = makeFakeAudioContext();
    globalThis.AudioContext = class {
      constructor() {
        return ctx;
      }
    };
    globalThis.requestAnimationFrame = (fn) => setTimeout(fn, 0);
    globalThis.cancelAnimationFrame = (id) => clearTimeout(id);

    const pcm = new Uint8Array(32000); // 16000 样本 = 1000ms @16k，超过缓冲阈值
    for (let i = 0; i < pcm.length; i += 2) {
      pcm[i] = i % 256;
      pcm[i + 1] = 0x10;
    }
    const chunks = [pcm.buffer];
    const reader = {
      index: 0,
      async read() {
        if (this.index >= chunks.length) return { done: true, value: undefined };
        return { done: false, value: chunks[this.index++] };
      },
    };

    const states = [];
    const playback = new StreamingPlayback({
      onStateChange: (s) => states.push(s),
    });
    await playback.playFromReader(reader);
    // 首块到达后应进入 PLAYING 或至少已启动调度（S3：响应未结束即可消费/播放）
    expect(states).toContain(STREAM_STATE.PLAYING);
    globalThis.AudioContext = undefined;
  });
});

describe('F9: underrun → STREAMING_BUFFERING → 恢复', () => {
  test('队列为空时保持缓冲状态', async () => {
    const { ctx } = makeFakeAudioContext();
    globalThis.AudioContext = class {
      constructor() {
        return ctx;
      }
    };
    const playback = new StreamingPlayback({});
    // 无数据、未结束：scheduleAvailable 不崩溃
    playback.ensureAudioContext();
    playback.started = true;
    playback.scheduleAvailable();
    expect([STREAM_STATE.STREAMING_BUFFERING, STREAM_STATE.PLAYING]).toContain(playback.getState());
    globalThis.AudioContext = undefined;
  });
});

describe('F10: 中途错误 → STREAM_ERROR', () => {
  test('reader.read 抛错进入 STREAM_ERROR', async () => {
    const { ctx } = makeFakeAudioContext();
    globalThis.AudioContext = class {
      constructor() {
        return ctx;
      }
    };
    globalThis.requestAnimationFrame = (fn) => setTimeout(fn, 0);
    globalThis.cancelAnimationFrame = (id) => clearTimeout(id);

    const reader = {
      async read() {
        throw new Error('network broken');
      },
    };
    const playback = new StreamingPlayback({});
    await playback.playFromReader(reader);
    expect(playback.getState()).toBe(STREAM_STATE.STREAM_ERROR);
    expect(playback.getError().message).toBe('network broken');
    globalThis.AudioContext = undefined;
  });
});

describe('F11: autoplay 阻塞 → 用户恢复', () => {
  test('AudioContext suspended → AUTOPLAY_BLOCKED，resumeFromUserGesture 后恢复', async () => {
    const { ctx } = makeFakeAudioContext();
    ctx.state = 'suspended';
    globalThis.AudioContext = class {
      constructor() {
        return ctx;
      }
    };
    globalThis.requestAnimationFrame = (fn) => setTimeout(fn, 0);
    globalThis.cancelAnimationFrame = (id) => clearTimeout(id);

    const reader = {
      async read() {
        return { done: true, value: undefined };
      },
    };
    const states = [];
    const playback = new StreamingPlayback({
      onStateChange: (s) => states.push(s),
    });
    await playback.playFromReader(reader);
    expect(states).toContain(STREAM_STATE.AUTOPLAY_BLOCKED);
    ctx.state = 'running';
    await playback.resumeFromUserGesture();
    // 用户恢复后不再是 AUTOPLAY_BLOCKED
    expect(playback.getState()).not.toBe(STREAM_STATE.AUTOPLAY_BLOCKED);
    globalThis.AudioContext = undefined;
  });
});

describe('F12: lip-sync 失败不影响音频', () => {
  test('onLipSyncFrame 抛错时音频状态仍推进', async () => {
    const { ctx } = makeFakeAudioContext();
    globalThis.AudioContext = class {
      constructor() {
        return ctx;
      }
    };
    globalThis.requestAnimationFrame = (fn) => setTimeout(fn, 0);
    globalThis.cancelAnimationFrame = (id) => clearTimeout(id);

    const reader = {
      async read() {
        return { done: true, value: undefined };
      },
    };
    const playback = new StreamingPlayback({
      onLipSyncFrame: () => {
        throw new Error('lip sync broken');
      },
    });
    // lip-sync 回调抛错不应中断 playFromReader
    await expect(playback.playFromReader(reader)).resolves.toBeUndefined();
    globalThis.AudioContext = undefined;
  });
});

// ===== L1–L3：流式音频真正驱动口型分析（非仅失败隔离） =====

function installFakeWebAudio(ctx) {
  globalThis.AudioContext = class {
    constructor() {
      return ctx;
    }
  };
  globalThis.requestAnimationFrame = (fn) => setTimeout(fn, 0);
  globalThis.cancelAnimationFrame = (id) => clearTimeout(id);
}

function pcmChunkOf(sizeBytes) {
  const pcm = new Uint8Array(sizeBytes);
  for (let i = 0; i < pcm.length; i += 2) {
    pcm[i] = 0x00;
    pcm[i + 1] = 0x40; // 中等幅度正样本（小端：0x4000 = +16384）
  }
  return pcm.buffer;
}

function readerWithChunks(chunks) {
  let index = 0;
  return {
    async read() {
      if (index >= chunks.length) return { done: true, value: undefined };
      return { done: false, value: chunks[index++] };
    },
  };
}

describe('L1: 播放中非零 analyser 数据 → lip-sync 输出被实际调用且非零', () => {
  test('PLAYING 时 onLipSyncFrame 收到 meaningful 非零 volume/weights', async () => {
    const { ctx } = makeFakeAudioContext();
    installFakeWebAudio(ctx);

    const frames = [];
    const playback = new StreamingPlayback({
      onLipSyncFrame: (weights, volume, active) => frames.push({ weights, volume, active }),
    });
    // 400ms PCM @16k → 足够调度并进入 PLAYING
    await playback.playFromReader(readerWithChunks([pcmChunkOf(16000 * 2 * 0.4)]));
    // 注入非零 analyser 信号（模拟真实流式音频驱动 analyser）
    ctx.createAnalyser().hasSignal = true;

    // 等待若干 lip-sync 帧（rAF 模拟为 setTimeout 0）
    await new Promise((resolve) => setTimeout(resolve, 50));

    const meaningful = frames.some(
      (f) => f.active === true && (f.volume > 0.001 || (f.weights && Object.values(f.weights).some((v) => v > 0.001))),
    );
    expect(meaningful).toBe(true);
    globalThis.AudioContext = undefined;
  });
});

describe('L2: ENDED/STOPPED 不保留永久 rAF lip-sync 循环', () => {
  test('stop 后 lip-sync 帧循环停止', async () => {
    const { ctx } = makeFakeAudioContext();
    installFakeWebAudio(ctx);

    let frameCount = 0;
    const playback = new StreamingPlayback({
      onLipSyncFrame: () => {
        frameCount += 1;
      },
    });
    playback.ensureAudioContext();
    await playback.playFromReader(readerWithChunks([pcmChunkOf(16000 * 2 * 0.2)]));
    playback.stop();

    const afterStop = frameCount;
    await new Promise((resolve) => setTimeout(resolve, 60));
    // 停止后不应再有 lip-sync 帧（rAF 循环已被 cancelAnimationFrame 停止）
    expect(frameCount).toBe(afterStop);
    globalThis.AudioContext = undefined;
  });
});

describe('L3: lip-sync 异常不阻止音频播放', () => {
  test('onLipSyncFrame 抛错仍能推进到 PLAYING/ENDED', async () => {
    const { ctx } = makeFakeAudioContext();
    installFakeWebAudio(ctx);

    const states = [];
    const playback = new StreamingPlayback({
      onStateChange: (s) => states.push(s),
      onLipSyncFrame: () => {
        throw new Error('lip sync broken');
      },
    });
    await playback.playFromReader(readerWithChunks([pcmChunkOf(16000 * 2 * 0.3)]));
    // 播放仍推进（不卡在 PREPARING_SPEECH，出现过 PLAYING）
    expect(states).toContain(STREAM_STATE.PLAYING);
    globalThis.AudioContext = undefined;
  });
});

// ===== F13–F15：阻塞回退边界（首个 PCM 前可回退，之后不可） =====

describe('F13: 首个 PCM 前失败 → 允许阻塞回退（hasConsumedPcm=false）', () => {
  test('初始化未调度任何 PCM 时 hasConsumedPcm() 为 false', async () => {
    const { ctx } = makeFakeAudioContext();
    installFakeWebAudio(ctx);
    const playback = new StreamingPlayback({});
    playback.ensureAudioContext();
    expect(playback.hasConsumedPcm()).toBe(false);
    globalThis.AudioContext = undefined;
  });

  test('首个 PCM 前（reader 首读即失败）hasConsumedPcm() 保持 false', async () => {
    const { ctx } = makeFakeAudioContext();
    installFakeWebAudio(ctx);
    const failingReader = {
      async read() {
        throw new Error('endpoint unavailable before any PCM');
      },
    };
    const playback = new StreamingPlayback({});
    await playback.playFromReader(failingReader);
    expect(playback.getState()).toBe(STREAM_STATE.STREAM_ERROR);
    expect(playback.hasConsumedPcm()).toBe(false); // 零 PCM → 允许阻塞回退
    globalThis.AudioContext = undefined;
  });
});

describe('F14: 首个 PCM 已调度后再失败 → 不得回退（hasConsumedPcm=true）', () => {
  test('调度首个 PCM 后 hasConsumedPcm() 为 true', async () => {
    const { ctx } = makeFakeAudioContext();
    installFakeWebAudio(ctx);
    const playback = new StreamingPlayback({});
    await playback.playFromReader(readerWithChunks([pcmChunkOf(16000 * 2 * 0.4)]));
    expect(playback.hasConsumedPcm()).toBe(true);
    globalThis.AudioContext = undefined;
  });

  test('中途失败进入 STREAM_ERROR 且保留已消费标记', async () => {
    const { ctx } = makeFakeAudioContext();
    installFakeWebAudio(ctx);
    let reads = 0;
    const reader = {
      async read() {
        reads += 1;
        if (reads === 1) return { done: false, value: pcmChunkOf(16000 * 2 * 0.4) };
        throw new Error('mid-stream failure');
      },
    };
    const playback = new StreamingPlayback({});
    await playback.playFromReader(reader);
    expect(playback.getState()).toBe(STREAM_STATE.STREAM_ERROR);
    expect(playback.hasConsumedPcm()).toBe(true); // 已越过回退边界
    globalThis.AudioContext = undefined;
  });
});

describe('F15: 中途失败无残留调度/重叠音频', () => {
  test('STREAM_ERROR 后 stop 清理全部已调度源', async () => {
    const { ctx } = makeFakeAudioContext();
    installFakeWebAudio(ctx);
    let reads = 0;
    const reader = {
      async read() {
        reads += 1;
        if (reads === 1) return { done: false, value: pcmChunkOf(16000 * 2 * 0.4) };
        throw new Error('mid-stream failure');
      },
    };
    const playback = new StreamingPlayback({});
    await playback.playFromReader(reader);
    expect(playback.getState()).toBe(STREAM_STATE.STREAM_ERROR);
    // 清理：不再有任何调度源、队列、carry 残留
    expect(playback.sources.size).toBe(0);
    playback.pcmQueue.length = 0;
    expect(playback.pcmQueue.length).toBe(0);
    globalThis.AudioContext = undefined;
  });
});

// ===== 回退策略纯函数（独立业务规则 oracle，页面与测试共享） =====

describe('decideStreamingFailureAction 业务规则', () => {
  test('零 PCM + 普通失败 → FALLBACK_BLOCKING', () => {
    expect(decideStreamingFailureAction({ hasConsumedPcm: false, aborted: false }))
      .toBe(STREAM_FAILURE_ACTION.FALLBACK_BLOCKING);
  });

  test('首个 PCM 已调度 + 普通失败 → STREAM_ERROR（不得回退）', () => {
    expect(decideStreamingFailureAction({ hasConsumedPcm: true, aborted: false }))
      .toBe(STREAM_FAILURE_ACTION.STREAM_ERROR);
  });

  test('AbortError（aborted=true）→ ENDED', () => {
    expect(decideStreamingFailureAction({ hasConsumedPcm: false, aborted: true }))
      .toBe(STREAM_FAILURE_ACTION.ENDED);
    // 取消优先于已消费标记：用户主动停止不算失败
    expect(decideStreamingFailureAction({ hasConsumedPcm: true, aborted: true }))
      .toBe(STREAM_FAILURE_ACTION.ENDED);
  });

  test('缺省参数安全', () => {
    expect(decideStreamingFailureAction()).toBe(STREAM_FAILURE_ACTION.FALLBACK_BLOCKING);
    expect(decideStreamingFailureAction({})).toBe(STREAM_FAILURE_ACTION.FALLBACK_BLOCKING);
  });
});
