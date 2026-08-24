package com.treepeople.leapmindtts.util;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * WAV 音频合并工具类
 * 正确处理 WAV 文件合并：剥离后续片段的文件头，只拼接 PCM 数据，最后重建统一的 WAV header。
 *
 * <p>WAV 文件结构（PCM，44 字节标准头）：
 * <pre>
 * 偏移 0-3   : "RIFF"
 * 偏移 4-7   : 文件大小 - 8
 * 偏移 8-11  : "WAVE"
 * 偏移 12-15 : "fmt "
 * 偏移 16-19 : fmt chunk 大小 (16 for PCM)
 * 偏移 20-21 : 音频格式 (1 = PCM)
 * 偏移 22-23 : 声道数
 * 偏移 24-27 : 采样率
 * 偏移 28-31 : 字节率
 * 偏移 32-33 : 块对齐
 * 偏移 34-35 : 位深度
 * 偏移 36-39 : "data"
 * 偏移 40-43 : 数据大小
 * 偏移 44+   : PCM 数据
 * </pre>
 */
@Slf4j
public final class WavMergeUtil {

    private static final int WAV_HEADER_SIZE = 44;
    private static final String RIFF = "RIFF";
    private static final String WAVE = "WAVE";

    private WavMergeUtil() {
    }

    /**
     * 判断字节数组是否为有效的 WAV 文件
     */
    public static boolean isWav(byte[] data) {
        if (data == null || data.length < WAV_HEADER_SIZE) {
            return false;
        }
        String riff = new String(data, 0, 4);
        String wave = new String(data, 8, 4);
        return RIFF.equals(riff) && WAVE.equals(wave);
    }

    /**
     * 合并多个 WAV 音频片段为一个完整的 WAV 文件。
     *
     * <p>处理逻辑：
     * <ol>
     *   <li>如果所有片段都是 WAV 格式，剥离每个片段的 header，只拼接 PCM 数据，重建统一 header</li>
     *   <li>如果片段不是 WAV 格式（如纯 PCM），直接拼接</li>
     *   <li>混合格式时，尝试将非 WAV 片段当作 PCM 数据处理</li>
     * </ol>
     *
     * @param audioSegments 音频片段列表
     * @return 合并后的 WAV 字节数组
     */
    public static byte[] mergeWavSegments(List<byte[]> audioSegments) {
        if (audioSegments == null || audioSegments.isEmpty()) {
            log.warn("音频片段列表为空，返回空数组");
            return new byte[0];
        }

        // 过滤空片段
        List<byte[]> validSegments = audioSegments.stream()
                .filter(data -> data != null && data.length > 0)
                .toList();

        if (validSegments.isEmpty()) {
            log.warn("所有音频片段为空，返回空数组");
            return new byte[0];
        }

        // 单个片段直接返回
        if (validSegments.size() == 1) {
            return validSegments.get(0);
        }

        // 检查是否所有片段都是 WAV 格式
        boolean allWav = validSegments.stream().allMatch(WavMergeUtil::isWav);

        if (allWav) {
            return mergeAllWav(validSegments);
        }

        // 混合格式：WAV 片段剥离 header 取 PCM，非 WAV 片段直接作为 PCM
        log.warn("音频片段格式混合，尝试按 PCM 合并");
        return mergeMixedFormat(validSegments);
    }

    /**
     * 合并所有 WAV 片段
     */
    private static byte[] mergeAllWav(List<byte[]> wavSegments) {
        // 从第一个片段提取 WAV 格式信息
        byte[] first = wavSegments.get(0);
        short numChannels = readShort(first, 22);
        int sampleRate = readInt(first, 24);
        short bitsPerSample = readShort(first, 34);

        // 拼接所有 PCM 数据（跳过 44 字节 header）
        ByteArrayOutputStream pcmStream = new ByteArrayOutputStream();
        for (int i = 0; i < wavSegments.size(); i++) {
            byte[] segment = wavSegments.get(i);
            int dataOffset = findDataChunk(segment);
            if (dataOffset < 0) {
                log.warn("片段 {} 无法定位 data chunk，跳过", i);
                continue;
            }
            int dataSize = readInt(segment, dataOffset - 4);
            // 防止 dataSize 异常（某些 WAV 文件 data size 可能不正确）
            int actualDataSize = Math.min(dataSize, segment.length - dataOffset);
            pcmStream.write(segment, dataOffset, actualDataSize);
        }

        byte[] pcmData = pcmStream.toByteArray();
        if (pcmData.length == 0) {
            log.error("合并后 PCM 数据为空");
            return new byte[0];
        }

        // 重建 WAV header
        byte[] wavHeader = buildWavHeader(pcmData.length, numChannels, sampleRate, bitsPerSample);

        // 拼接 header + PCM 数据
        byte[] result = new byte[wavHeader.length + pcmData.length];
        System.arraycopy(wavHeader, 0, result, 0, wavHeader.length);
        System.arraycopy(pcmData, 0, result, wavHeader.length, pcmData.length);

        log.info("WAV 合并完成，片段数: {}, PCM 数据大小: {} bytes, 总大小: {} bytes",
                wavSegments.size(), pcmData.length, result.length);
        return result;
    }

    /**
     * 混合格式合并：WAV 片段取 PCM 部分，非 WAV 片段直接拼接
     */
    private static byte[] mergeMixedFormat(List<byte[]> segments) {
        ByteArrayOutputStream pcmStream = new ByteArrayOutputStream();
        short numChannels = 1;
        int sampleRate = 16000;
        short bitsPerSample = 16;
        boolean formatDetected = false;

        for (int i = 0; i < segments.size(); i++) {
            byte[] segment = segments.get(i);
            if (isWav(segment)) {
                if (!formatDetected) {
                    numChannels = readShort(segment, 22);
                    sampleRate = readInt(segment, 24);
                    bitsPerSample = readShort(segment, 34);
                    formatDetected = true;
                }
                int dataOffset = findDataChunk(segment);
                if (dataOffset >= 0) {
                    int dataSize = Math.min(readInt(segment, dataOffset - 4), segment.length - dataOffset);
                    pcmStream.write(segment, dataOffset, dataSize);
                }
            } else {
                // 非 WAV，当作裸 PCM 数据
                pcmStream.write(segment, 0, segment.length);
            }
        }

        byte[] pcmData = pcmStream.toByteArray();
        if (pcmData.length == 0) {
            return new byte[0];
        }

        byte[] wavHeader = buildWavHeader(pcmData.length, numChannels, sampleRate, bitsPerSample);
        byte[] result = new byte[wavHeader.length + pcmData.length];
        System.arraycopy(wavHeader, 0, result, 0, wavHeader.length);
        System.arraycopy(pcmData, 0, result, wavHeader.length, pcmData.length);

        log.info("混合格式合并完成，总大小: {} bytes", result.length);
        return result;
    }

    /**
     * 查找 WAV 文件中 "data" chunk 的起始位置
     *
     * @param wavData WAV 文件字节数组
     * @return data chunk 数据部分的起始偏移量，找不到返回 -1
     */
    private static int findDataChunk(byte[] wavData) {
        // 标准 WAV header 的 data chunk 在偏移 36 处
        if (wavData.length >= WAV_HEADER_SIZE) {
            String dataMarker = new String(wavData, 36, 4);
            if ("data".equals(dataMarker)) {
                return WAV_HEADER_SIZE; // 标准格式，data 从偏移 44 开始
            }
        }

        // 非标准格式，搜索 "data" 标记
        for (int i = 12; i < wavData.length - 8; i++) {
            if (wavData[i] == 'd' && wavData[i + 1] == 'a' &&
                wavData[i + 2] == 't' && wavData[i + 3] == 'a') {
                return i + 8; // 跳过 "data" 标记(4字节) + 数据大小(4字节)
            }
        }
        return -1;
    }

    /**
     * 获取 WAV 文件中 PCM 数据的起始偏移量（public，供流式输出场景使用）
     *
     * @param wavData WAV 文件字节数组
     * @return PCM 数据起始偏移量，不是 WAV 或找不到返回 -1
     */
    public static int getPcmDataOffset(byte[] wavData) {
        if (!isWav(wavData)) return -1;
        return findDataChunk(wavData);
    }

    /**
     * 构建 WAV 文件头（public，供流式输出场景使用）
     */
    public static byte[] buildWavHeader(int pcmDataSize, short numChannels, int sampleRate, short bitsPerSample) {
        int byteRate = sampleRate * numChannels * bitsPerSample / 8;
        short blockAlign = (short) (numChannels * bitsPerSample / 8);
        int chunkSize = 36 + pcmDataSize; // 文件大小 - 8

        ByteBuffer buffer = ByteBuffer.allocate(WAV_HEADER_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        buffer.put(RIFF.getBytes());           // 0-3: "RIFF"
        buffer.putInt(chunkSize);              // 4-7: 文件大小 - 8
        buffer.put(WAVE.getBytes());           // 8-11: "WAVE"

        // fmt chunk
        buffer.put("fmt ".getBytes());         // 12-15: "fmt "
        buffer.putInt(16);                     // 16-19: fmt chunk 大小
        buffer.putShort((short) 1);            // 20-21: 音频格式 (PCM)
        buffer.putShort(numChannels);          // 22-23: 声道数
        buffer.putInt(sampleRate);             // 24-27: 采样率
        buffer.putInt(byteRate);               // 28-31: 字节率
        buffer.putShort(blockAlign);           // 32-33: 块对齐
        buffer.putShort(bitsPerSample);        // 34-35: 位深度

        // data chunk
        buffer.put("data".getBytes());         // 36-39: "data"
        buffer.putInt(pcmDataSize);            // 40-43: 数据大小

        return buffer.array();
    }

    /**
     * 从 WAV 字节数组中读取 little-endian short
     */
    private static short readShort(byte[] data, int offset) {
        return (short) ((data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8));
    }

    /**
     * 从 WAV 字节数组中读取 little-endian int
     */
    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) |
                ((data[offset + 1] & 0xFF) << 8) |
                ((data[offset + 2] & 0xFF) << 16) |
                ((data[offset + 3] & 0xFF) << 24);
    }

    /**
     * 从 WAV 文件头解析采样率
     */
    public static int getSampleRate(byte[] wavData) {
        if (!isWav(wavData)) return 16000;
        return readInt(wavData, 24);
    }

    /**
     * 从 WAV 文件头解析声道数
     */
    public static short getChannels(byte[] wavData) {
        if (!isWav(wavData)) return 1;
        return readShort(wavData, 22);
    }

    /**
     * 从 WAV 文件头解析位深度
     */
    public static short getBitsPerSample(byte[] wavData) {
        if (!isWav(wavData)) return 16;
        return readShort(wavData, 34);
    }

    /**
     * 获取 WAV 文件中 PCM 数据的大小（不含 header）
     */
    public static int getPcmDataSize(byte[] wavData) {
        if (!isWav(wavData)) return wavData.length;
        int dataOffset = findDataChunk(wavData);
        if (dataOffset < 0) return wavData.length - WAV_HEADER_SIZE;
        return Math.min(readInt(wavData, dataOffset - 4), wavData.length - dataOffset);
    }

    /**
     * 估算 WAV 音频时长（毫秒）
     *
     * @param wavData WAV 文件字节数组
     * @return 时长（毫秒），解析失败返回基于数据大小的估算值
     */
    public static long estimateDurationMs(byte[] wavData) {
        if (wavData == null || wavData.length == 0) {
            return 0;
        }

        if (isWav(wavData)) {
            int pcmSize = getPcmDataSize(wavData);
            int sampleRate = getSampleRate(wavData);
            short channels = getChannels(wavData);
            short bitsPerSample = getBitsPerSample(wavData);

            int bytesPerSample = channels * bitsPerSample / 8;
            if (bytesPerSample > 0 && sampleRate > 0) {
                long durationMs = (long) ((pcmSize / (double) (sampleRate * bytesPerSample)) * 1000);
                return Math.max(durationMs, 100);
            }
        }

        // 非 WAV 文件，按 16kHz/16bit/单声道 估算
        long estimatedMs = (wavData.length * 1000L) / 32000;
        return Math.max(estimatedMs, 100);
    }
}
