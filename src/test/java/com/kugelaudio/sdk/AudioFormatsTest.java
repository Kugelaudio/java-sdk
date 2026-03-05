package com.kugelaudio.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AudioFormatsTest {

    @Test
    void ulawRoundTripPreservesLength() {
        byte[] pcm16 = new byte[16000];
        byte[] ulaw = AudioFormats.pcm16ToUlaw(pcm16);
        byte[] pcmBack = AudioFormats.ulawToPcm16(ulaw);
        assertEquals(8000, ulaw.length);
        assertEquals(16000, pcmBack.length);
    }

    @Test
    void durationCalculation() {
        byte[] pcm16 = new byte[16000];
        int ms = AudioFormats.durationMs(pcm16, 8000, 16, 1);
        assertTrue(ms >= 990 && ms <= 1010, "Expected ~1000ms, got " + ms);
    }

    @Test
    void durationWith24kHz() {
        byte[] pcm16 = new byte[48000];
        int ms = AudioFormats.durationMs(pcm16, 24000, 16, 1);
        assertTrue(ms >= 990 && ms <= 1010, "Expected ~1000ms, got " + ms);
    }

    @Test
    void durationWithNullReturnsZero() {
        assertEquals(0, AudioFormats.durationMs(null, 8000, 16, 1));
    }

    @Test
    void writePcm16WavCreatesValidFile(@TempDir Path tempDir) throws Exception {
        byte[] pcm16 = new byte[4800];
        Path outPath = tempDir.resolve("test.wav");
        AudioFormats.writePcm16Wav(outPath, pcm16, 24000, (short) 1);

        assertTrue(Files.exists(outPath));
        byte[] wavBytes = Files.readAllBytes(outPath);
        assertEquals(44 + pcm16.length, wavBytes.length);

        assertEquals('R', (char) wavBytes[0]);
        assertEquals('I', (char) wavBytes[1]);
        assertEquals('F', (char) wavBytes[2]);
        assertEquals('F', (char) wavBytes[3]);
        assertEquals('W', (char) wavBytes[8]);
        assertEquals('A', (char) wavBytes[9]);
        assertEquals('V', (char) wavBytes[10]);
        assertEquals('E', (char) wavBytes[11]);
    }

    @Test
    void ulawWithNullInput() {
        assertArrayEquals(new byte[0], AudioFormats.pcm16ToUlaw(null));
        assertArrayEquals(new byte[0], AudioFormats.ulawToPcm16(null));
    }

    @Test
    void ulawWithEmptyInput() {
        assertArrayEquals(new byte[0], AudioFormats.pcm16ToUlaw(new byte[0]));
        assertArrayEquals(new byte[0], AudioFormats.ulawToPcm16(new byte[0]));
    }
}
