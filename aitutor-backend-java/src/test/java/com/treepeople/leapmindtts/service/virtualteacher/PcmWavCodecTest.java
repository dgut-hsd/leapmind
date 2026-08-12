package com.treepeople.leapmindtts.service.virtualteacher;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PCM → WAV 封装 与 WAV → PCM 解析的编解码测试（B11–B14）。
 */
class PcmWavCodecTest {

    private static final byte[] SAMPLE_PCM = {
            0x00, 0x00,
            (byte) 0xFF, 0x7F,   // +32767
            (byte) 0x01, (byte) 0x80, // -32767
            0x22, 0x11,
    };

    // ---------- B11: PCM→WAV header correctness ----------

    @Test
    void pcmToWavProducesValidRiffHeader() {
        byte[] wav = PcmWavEncoder.encodeWav(SAMPLE_PCM, 16000, (short) 1, (short) 16);

        // RIFF / WAVE
        assertEquals("RIFF", new String(wav, 0, 4));
        assertEquals("WAVE", new String(wav, 8, 4));
        // fmt chunk
        assertEquals("fmt ", new String(wav, 12, 4));
        assertEquals(16, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(16));
        assertEquals(1, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getShort(20)); // PCM
        assertEquals(1, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getShort(22)); // channels
        assertEquals(16000, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(24)); // sample rate
        assertEquals(32000, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(28)); // byte rate
        assertEquals(2, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getShort(32)); // block align
        assertEquals(16, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getShort(34)); // bits
        // data chunk
        assertEquals("data", new String(wav, 36, 4));
        assertEquals(SAMPLE_PCM.length, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40));
        // RIFF length = 36 + data
        assertEquals(36 + SAMPLE_PCM.length, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(4));
        // PCM payload unchanged
        byte[] payload = new byte[SAMPLE_PCM.length];
        System.arraycopy(wav, 44, payload, 0, SAMPLE_PCM.length);
        assertArrayEquals(SAMPLE_PCM, payload);
    }

    @Test
    void pcmToWavRejectsInvalidMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> PcmWavEncoder.encodeWav(new byte[0], 16000, (short) 1, (short) 16));
        assertThrows(IllegalArgumentException.class,
                () -> PcmWavEncoder.encodeWav(SAMPLE_PCM, 0, (short) 1, (short) 16));
        assertThrows(IllegalArgumentException.class,
                () -> PcmWavEncoder.encodeWav(SAMPLE_PCM, 16000, (short) 2, (short) 16));
        assertThrows(IllegalArgumentException.class,
                () -> PcmWavEncoder.encodeWav(SAMPLE_PCM, 16000, (short) 1, (short) 8));
        assertThrows(IllegalArgumentException.class,
                () -> PcmWavEncoder.encodeWav(new byte[]{1, 2, 3}, 16000, (short) 1, (short) 16));
    }

    // ---------- B12: standard 44-byte header WAV parses ----------

    @Test
    void wavWithStandardHeaderParsesPcm() {
        byte[] wav = PcmWavEncoder.encodeWav(SAMPLE_PCM, 16000, (short) 1, (short) 16);
        WavPcmParser.PcmPayload payload = WavPcmParser.parse(wav);

        assertEquals(16000, payload.sampleRate());
        assertEquals(1, payload.channels());
        assertEquals(16, payload.bitsPerSample());
        assertTrue(payload.matchesStreamingContract());
        assertArrayEquals(SAMPLE_PCM, payload.pcm());
    }

    // ---------- B13: parser does not assume 44-byte header ----------

    @Test
    void wavWithLeadingUnrelatedChunkParsesPcm() {
        // RIFF/WAVE + JUNK chunk + fmt + data
        byte[] pcm = SAMPLE_PCM;
        ByteBuffer wav = ByteBuffer.allocate(12 + 8 + 8 + 8 + 16 + 8 + pcm.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes());
        wav.putInt(36 + 8 + pcm.length); // riff size (excluding JUNK body accounting; recomputed below)
        wav.put("WAVE".getBytes());
        wav.put("JUNK".getBytes());
        wav.putInt(8);
        wav.put(new byte[8]);
        wav.put("fmt ".getBytes());
        wav.putInt(16);
        wav.putShort((short) 1);
        wav.putShort((short) 1);
        wav.putInt(16000);
        wav.putInt(32000);
        wav.putShort((short) 2);
        wav.putShort((short) 16);
        wav.put("data".getBytes());
        wav.putInt(pcm.length);
        wav.put(pcm);

        WavPcmParser.PcmPayload payload = WavPcmParser.parse(wav.array());
        assertEquals(16000, payload.sampleRate());
        assertEquals(1, payload.channels());
        assertEquals(16, payload.bitsPerSample());
        assertArrayEquals(pcm, payload.pcm());
    }

    // ---------- B14: invalid / incompatible WAV handling ----------

    @Test
    void nonWavBytesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> WavPcmParser.parse(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}));
    }

    @Test
    void nonPcmFormatRejected() {
        // fmt with audioFormat=3 (IEEE float)
        ByteBuffer wav = ByteBuffer.allocate(12 + 8 + 16 + 8 + 4).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes());
        wav.putInt(36 + 4);
        wav.put("WAVE".getBytes());
        wav.put("fmt ".getBytes());
        wav.putInt(16);
        wav.putShort((short) 3); // IEEE float
        wav.putShort((short) 1);
        wav.putInt(16000);
        wav.putInt(64000);
        wav.putShort((short) 4);
        wav.putShort((short) 32);
        wav.put("data".getBytes());
        wav.putInt(4);
        wav.put(new byte[4]);

        assertThrows(IllegalArgumentException.class, () -> WavPcmParser.parse(wav.array()));
    }

    @Test
    void incompatibleChannelsMarkedNotMatchingContract() {
        byte[] wav = PcmWavEncoder.encodeWav(SAMPLE_PCM, 16000, (short) 1, (short) 16);
        WavPcmParser.PcmPayload payload = WavPcmParser.parse(wav);
        assertTrue(payload.matchesStreamingContract());

        // stereo payload → not matching streaming contract
        WavPcmParser.PcmPayload stereo = new WavPcmParser.PcmPayload(payload.pcm(), 16000, (short) 2, (short) 16);
        assertFalse(stereo.matchesStreamingContract());
    }
}
