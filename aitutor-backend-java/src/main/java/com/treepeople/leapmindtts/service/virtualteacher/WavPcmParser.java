package com.treepeople.leapmindtts.service.virtualteacher;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * M8-owned minimal RIFF/WAVE parser.
 *
 * <p>Locates RIFF/WAVE/fmt/data chunks and extracts the raw PCM payload.
 * Does NOT assume a fixed 44-byte header: unrelated chunks before {@code data}
 * are tolerated when standards-valid (provider-generated WAVs may differ).
 */
public final class WavPcmParser {

    /** Expected streaming endpoint format (S16 mono 16k). */
    public static final short FORMAT_PCM = 1;
    public static final short CHANNELS_MONO = 1;
    public static final int SAMPLE_RATE_16K = 16000;
    public static final short BITS_PER_SAMPLE_16 = 16;

    /**
     * Parsed PCM metadata + payload.
     */
    public record PcmPayload(byte[] pcm, int sampleRate, short channels, short bitsPerSample) {
        public boolean matchesStreamingContract() {
            return channels == CHANNELS_MONO
                    && sampleRate == SAMPLE_RATE_16K
                    && bitsPerSample == BITS_PER_SAMPLE_16;
        }
    }

    private WavPcmParser() {
    }

    /**
     * Parse a WAV byte array and extract the PCM payload.
     *
     * @param wav WAV bytes
     * @return parsed payload
     * @throws IllegalArgumentException if the bytes are not a parseable PCM WAV
     */
    public static PcmPayload parse(byte[] wav) {
        if (wav == null || wav.length < 12) {
            throw new IllegalArgumentException("WAV 数据过短或为空");
        }
        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);

        if (!"RIFF".equals(sliceAsString(wav, 0, 4))) {
            throw new IllegalArgumentException("非 RIFF 文件头");
        }
        if (!"WAVE".equals(sliceAsString(wav, 8, 4))) {
            throw new IllegalArgumentException("非 WAVE 格式");
        }

        int sampleRate = 0;
        short channels = 0;
        short bitsPerSample = 0;
        short audioFormat = -1;
        boolean fmtSeen = false;

        int dataStart = -1;
        int dataSize = 0;

        int pos = 12;
        while (pos + 8 <= wav.length) {
            String chunkId = sliceAsString(wav, pos, 4);
            int chunkSize = buf.getInt(pos + 4);
            int chunkBodyStart = pos + 8;
            if (chunkSize < 0 || chunkBodyStart + chunkSize > wav.length) {
                // Truncated chunk: stop scanning defensively.
                break;
            }
            switch (chunkId) {
                case "fmt " -> {
                    if (chunkSize >= 16) {
                        audioFormat = buf.getShort(chunkBodyStart);
                        channels = buf.getShort(chunkBodyStart + 2);
                        sampleRate = buf.getInt(chunkBodyStart + 4);
                        bitsPerSample = buf.getShort(chunkBodyStart + 14);
                        fmtSeen = true;
                    }
                }
                case "data" -> {
                    dataStart = chunkBodyStart;
                    dataSize = chunkSize;
                    break;
                }
                default -> {
                    // unrelated chunk (LIST, JUNK, ...) — tolerated before data
                }
            }
            if (dataStart >= 0) {
                break;
            }
            pos = chunkBodyStart + chunkSize + (chunkSize % 2); // chunks are word-aligned
        }

        if (!fmtSeen || audioFormat != FORMAT_PCM) {
            throw new IllegalArgumentException("非 PCM 编码 WAV，audioFormat=" + audioFormat);
        }
        if (dataStart < 0 || dataSize <= 0) {
            throw new IllegalArgumentException("WAV 中未找到有效的 data chunk");
        }
        if (dataStart + dataSize > wav.length) {
            dataSize = wav.length - dataStart; // defensive truncation guard
        }

        byte[] pcm = new byte[dataSize];
        System.arraycopy(wav, dataStart, pcm, 0, dataSize);
        return new PcmPayload(pcm, sampleRate, channels, bitsPerSample);
    }

    private static String sliceAsString(byte[] data, int offset, int length) {
        return new String(data, offset, length, StandardCharsets.US_ASCII);
    }
}
