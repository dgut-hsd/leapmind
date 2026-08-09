package com.treepeople.leapmindtts.service.virtualteacher;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * M8-owned minimal PCM → WAV encoder.
 *
 * <p>Wraps validated raw PCM (S16LE mono 16kHz) into a standards-valid RIFF/WAVE
 * artifact for the existing storage/cache contract (hash.wav). Deliberately small;
 * does NOT depend on {@code WavMergeUtil} (untracked worktree file).
 */
public final class PcmWavEncoder {

    public static final short FORMAT_PCM = 1;
    public static final short CHANNELS_MONO = 1;
    public static final int SAMPLE_RATE_16K = 16000;
    public static final short BITS_PER_SAMPLE_16 = 16;

    private PcmWavEncoder() {
    }

    /**
     * Encode raw PCM bytes into a WAV container.
     *
     * @param pcm          little-endian 16-bit signed mono PCM samples
     * @param sampleRate   sample rate in Hz (must be &gt; 0)
     * @param channels     channel count (must be 1)
     * @param bitsPerSample bits per sample (must be 16)
     * @return valid WAV bytes
     * @throws IllegalArgumentException on invalid PCM metadata
     */
    public static byte[] encodeWav(byte[] pcm, int sampleRate, short channels, short bitsPerSample) {
        if (pcm == null || pcm.length == 0) {
            throw new IllegalArgumentException("PCM 数据为空，无法封装为 WAV");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("非法采样率: " + sampleRate);
        }
        if (channels != CHANNELS_MONO) {
            throw new IllegalArgumentException("仅支持单声道 PCM: " + channels);
        }
        if (bitsPerSample != BITS_PER_SAMPLE_16) {
            throw new IllegalArgumentException("仅支持 16-bit PCM: " + bitsPerSample);
        }
        if (pcm.length % 2 != 0) {
            throw new IllegalArgumentException("PCM 字节数必须为偶数（16-bit 采样）: " + pcm.length);
        }

        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = pcm.length;
        int riffChunkSize = 36 + dataSize;

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize);
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);

        header.put("RIFF".getBytes());
        header.putInt(riffChunkSize);
        header.put("WAVE".getBytes());
        header.put("fmt ".getBytes());
        header.putInt(16);                          // fmt chunk size (PCM)
        header.putShort(FORMAT_PCM);                // audio format = PCM
        header.putShort(channels);
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort((short) blockAlign);
        header.putShort(bitsPerSample);
        header.put("data".getBytes());
        header.putInt(dataSize);

        out.writeBytes(header.array());
        out.writeBytes(pcm);
        return out.toByteArray();
    }
}
