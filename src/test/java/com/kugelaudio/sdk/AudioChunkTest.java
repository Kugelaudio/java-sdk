package com.kugelaudio.sdk;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class AudioChunkTest {

    @Test
    void fromServerMessageDecodesBase64() {
        byte[] pcm16 = {0x00, 0x10, 0x00, 0x20};
        String base64 = Base64.getEncoder().encodeToString(pcm16);

        AudioChunk chunk = AudioChunk.fromServerMessage(base64, 0, 24000, 2);

        assertArrayEquals(pcm16, chunk.getAudio());
        assertEquals(0, chunk.getIndex());
        assertEquals(24000, chunk.getSampleRate());
        assertEquals(2, chunk.getSamples());
    }

    @Test
    void toFloat32NormalizesCorrectly() {
        // Max positive PCM16 sample: 0x7FFF = 32767
        byte[] pcm16 = {(byte) 0xFF, (byte) 0x7F};
        AudioChunk chunk = new AudioChunk(pcm16, 0, 24000, 1);

        float[] floats = chunk.toFloat32();
        assertEquals(1, floats.length);
        assertTrue(floats[0] > 0.99f && floats[0] <= 1.0f,
                "Expected near 1.0, got " + floats[0]);
    }

    @Test
    void toFloat32SilenceIsZero() {
        byte[] pcm16 = {0x00, 0x00, 0x00, 0x00};
        AudioChunk chunk = new AudioChunk(pcm16, 0, 24000, 2);

        float[] floats = chunk.toFloat32();
        assertEquals(2, floats.length);
        assertEquals(0.0f, floats[0]);
        assertEquals(0.0f, floats[1]);
    }
}
