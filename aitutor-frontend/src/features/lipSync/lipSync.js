const TIME_DOMAIN_DATA_LENGTH = 2048;
const DEFAULT_FFT_SIZE = 2048;

export class LipSync {
  constructor(audio) {
    this.audio = audio;

    this.analyser = audio.createAnalyser();
    this.analyser.fftSize = DEFAULT_FFT_SIZE;
    this.analyser.smoothingTimeConstant = 0.3; // 低平滑，快速响应字间停顿
    this.timeDomainData = new Float32Array(TIME_DOMAIN_DATA_LENGTH);
    this.frequencyData = new Uint8Array(this.analyser.frequencyBinCount);
    this.currentSource = null; // 跟踪当前音频源
    this.microphoneSource = null; // 麦克风音频源
    this.isMicrophoneConnected = false; // 麦克风连接状态

    this._prevVolume = 0; // 音量平滑（包络）
    this._lastActive = false; // 上一帧是否处于说话/录音活跃
    this.mediaElementSource = null; // HTMLAudioElement 源（通过 AudioContext 输出）
    this.mediaElementGain = null; // 媒体元素的输出增益（通往 destination）
    this._mediaElementRef = null; // 保存引用用于判断播放状态
  }

  update() {
    // 认为当前是否有活跃音源（播放中或麦克风连接）
    const mediaPlaying = !!(this._mediaElementRef && !this._mediaElementRef.paused && !this._mediaElementRef.ended);
    const active = !!this.currentSource || this.isMicrophoneConnected || mediaPlaying;

    // 非活跃状态：立即衰减到0（不等第二帧）
    if (!active) {
      this._prevVolume *= 0.6;
      if (this._prevVolume < 0.001) this._prevVolume = 0;
      this._lastActive = false;
      return { volume: this._prevVolume, weights: undefined, active: false };
    }

    // 时域数据用于音量（包络）
    this.analyser.getFloatTimeDomainData(this.timeDomainData);

    let peak = 0.0;
    let rms = 0.0;
    for (let i = 0; i < TIME_DOMAIN_DATA_LENGTH; i++) {
      const sample = this.timeDomainData[i];
      peak = Math.max(peak, Math.abs(sample));
      rms += sample * sample;
    }
    rms = Math.sqrt(rms / TIME_DOMAIN_DATA_LENGTH);
    const combinedVolume = peak * 0.5 + rms * 0.5;

    // 线性映射 + 高门限，避免底噪被放大成张嘴
    let env;
    if (combinedVolume < 0.015) {
      env = 0; // 静音门限：低于此值完全闭嘴
    } else {
      env = Math.min(1.0, (combinedVolume - 0.015) / 0.08);
    }

    // Attack/Release 平滑
    const attack = 0.4;
    const release = 0.5; // 下降比上升快，确保字间停顿能闭嘴
    if (env > this._prevVolume) {
      this._prevVolume = this._prevVolume + (env - this._prevVolume) * attack;
    } else {
      this._prevVolume = this._prevVolume + (env - this._prevVolume) * release;
    }

    // 频域数据用于元音估计
    this.analyser.getByteFrequencyData(this.frequencyData);
    const weights = this._estimateVowelWeights(this.frequencyData, this._prevVolume);

    this._lastActive = active;
    return { volume: this._prevVolume, weights, active };
  }

  async playFromArrayBuffer(buffer, onEnded) {
    // 确保AudioContext处于运行状态（有些浏览器需用户交互触发后resume）
    if (this.audio.state === 'suspended') {
      try { await this.audio.resume(); } catch (_) {}
    }
    // 停止并清理之前的音频源
    if (this.currentSource) {
      try {
        this.currentSource.stop();
        this.currentSource.disconnect();
      } catch (e) {
        // 忽略已经停止的音频源错误
      }
    }

    const audioBuffer = await this.audio.decodeAudioData(buffer);

    const bufferSource = this.audio.createBufferSource();
    bufferSource.buffer = audioBuffer;
    this.currentSource = bufferSource;

    bufferSource.connect(this.audio.destination);
    bufferSource.connect(this.analyser);
    bufferSource.start();
    
    // 音频结束时清理引用
    bufferSource.addEventListener("ended", () => {
      this.currentSource = null;
      if (onEnded) {
        onEnded();
      }
    });
  }

  async playFromURL(url, onEnded) {
    const res = await fetch(url);
    const buffer = await res.arrayBuffer();
    this.playFromArrayBuffer(buffer, onEnded);
  }

  // 连接麦克风进行实时嘴型同步
  async connectMicrophone() {
    try {
      // 确保AudioContext处于运行状态
      if (this.audio.state === 'suspended') {
        await this.audio.resume();
        console.log('AudioContext resumed for microphone');
      }

      // 请求麦克风权限
      const stream = await navigator.mediaDevices.getUserMedia({ 
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true
        } 
      });
      
      // 断开之前的麦克风连接
      this.disconnectMicrophone();
      
      // 创建麦克风音频源
      this.microphoneSource = this.audio.createMediaStreamSource(stream);
      
      // 连接到分析器
      this.microphoneSource.connect(this.analyser);
      
      this.isMicrophoneConnected = true;
      console.log('Microphone connected for lip sync');
      
      return true;
    } catch (error) {
      console.error('Failed to connect microphone for lip sync:', error);
      this.isMicrophoneConnected = false;
      return false;
    }
  }

  // 断开麦克风连接
  disconnectMicrophone() {
    if (this.microphoneSource) {
      try {
        this.microphoneSource.disconnect();
        this.microphoneSource = null;
        console.log('Microphone disconnected');
      } catch (error) {
        console.warn('Error disconnecting microphone:', error);
      }
    }
    this.isMicrophoneConnected = false;
  }

  // 检查麦克风是否已连接
  isMicConnected() {
    return this.isMicrophoneConnected;
  }

  // 基于频段能量比的元音估计，针对中文 TTS 优化
  _estimateVowelWeights(freq, volume) {
    if (volume === 0) {
      return undefined;
    }

    // 将频谱分为三个频段计算能量
    // low: 0-800Hz (开口音 aa/oh), mid: 800-2500Hz (半开音 ou), high: 2500Hz+ (闭口音 ee/ih)
    const sampleRate = this.audio.sampleRate || 44100;
    const binWidth = (sampleRate / 2) / freq.length;
    const lowEnd = Math.floor(800 / binWidth);
    const midEnd = Math.floor(2500 / binWidth);

    let lowEnergy = 0, midEnergy = 0, highEnergy = 0;
    for (let i = 0; i < freq.length; i++) {
      const mag = freq[i] / 255;
      if (i < lowEnd) lowEnergy += mag * mag;
      else if (i < midEnd) midEnergy += mag * mag;
      else highEnergy += mag * mag;
    }
    lowEnergy /= Math.max(1, lowEnd);
    midEnergy /= Math.max(1, midEnd - lowEnd);
    highEnergy /= Math.max(1, freq.length - midEnd);

    const total = lowEnergy + midEnergy + highEnergy;
    if (total < 1e-6) return undefined;

    // 主要用音量驱动张嘴，频段只做微调
    const aaRatio = lowEnergy / total;
    const ohRatio = midEnergy / total;
    const eeRatio = highEnergy / total;

    return {
      aa: volume * (0.5 + aaRatio * 0.5),
      oh: volume * ohRatio * 0.4,
      ou: volume * ohRatio * 0.3,
      ee: volume * eeRatio * 0.3,
      ih: volume * eeRatio * 0.2,
    };
  }

  _clamp01(v) {
    return Math.max(0, Math.min(1, v));
  }

  // 使用现有的 <audio> 元素作为输入源，仅连接到 analyser，不改变其播放设备
  attachMediaElement(audioElement) {
    try {
      if (!audioElement) return false;
      // resume 确保能创建节点
      if (this.audio.state === 'suspended') {
        this.audio.resume().catch(() => {});
      }
      // 如果先前已连接，先断开
      this.detachMediaElement();
      this.mediaElementSource = this.audio.createMediaElementSource(audioElement);
      // 路由到分析器
      this.mediaElementSource.connect(this.analyser);
      // 通过 WebAudio 输出到设备，避免浏览器实现差异造成静音
      this.mediaElementGain = this.audio.createGain();
      this.mediaElementGain.gain.value = 1.0;
      this.mediaElementSource.connect(this.mediaElementGain);
      this.mediaElementGain.connect(this.audio.destination);
      // 静音 DOM 元素本身，防止双路径回声
      try { audioElement.volume = 0; } catch {}
      this._mediaElementRef = audioElement;
      return true;
    } catch (e) {
      console.warn('attachMediaElement failed:', e);
      return false;
    }
  }

  detachMediaElement() {
    if (this.mediaElementSource) {
      try {
        this.mediaElementSource.disconnect();
      } catch {}
      this.mediaElementSource = null;
    }
    if (this.mediaElementGain) {
      try { this.mediaElementGain.disconnect(); } catch {}
      this.mediaElementGain = null;
    }
    if (this._mediaElementRef) {
      try { this._mediaElementRef.volume = 1.0; } catch {}
    }
    this._mediaElementRef = null;
  }
}