package com.kugelaudio.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AudioResponseTest {

    @Test
    void builderCreatesResponse() {
        byte[] audio = new byte[4800];
        AudioResponse response = AudioResponse.builder()
                .audio(audio)
                .sampleRate(24000)
                .totalSamples(2400)
                .durationMs(100.0)
                .generationMs(50.0)
                .rtf(0.5)
                .build();

        assertArrayEquals(audio, response.getAudio());
        assertEquals(24000, response.getSampleRate());
        assertEquals(2400, response.getTotalSamples());
        assertEquals(100.0, response.getDurationMs());
        assertEquals(50.0, response.getGenerationMs());
        assertEquals(0.5, response.getRtf());
    }

    @Test
    void toWavBytesCreatesValidHeader() {
        byte[] audio = new byte[100];
        AudioResponse response = AudioResponse.builder()
                .audio(audio)
                .sampleRate(24000)
                .build();

        byte[] wav = response.toWavBytes();
        assertEquals(44 + 100, wav.length);

        assertEquals('R', (char) wav[0]);
        assertEquals('I', (char) wav[1]);
        assertEquals('F', (char) wav[2]);
        assertEquals('F', (char) wav[3]);

        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(22);
        assertEquals(1, buf.getShort()); // mono
        assertEquals(24000, buf.getInt()); // sample rate
    }

    @Test
    void saveWavWritesToDisk(@TempDir Path tempDir) throws Exception {
        byte[] audio = new byte[2400];
        AudioResponse response = AudioResponse.builder()
                .audio(audio)
                .sampleRate(24000)
                .build();

        Path outPath = tempDir.resolve("test.wav");
        response.saveWav(outPath);

        assertTrue(Files.exists(outPath));
        assertEquals(44 + 2400, Files.size(outPath));
    }

    @Test
    void wordTimestampsAreImmutable() {
        AudioResponse response = AudioResponse.builder()
                .audio(new byte[0])
                .addWordTimestamps(List.of(
                        new WordTimestamp("hello", 0, 300, 0, 5, 0.95)
                ))
                .build();

        assertEquals(1, response.getWordTimestamps().size());
        assertThrows(UnsupportedOperationException.class, () ->
                response.getWordTimestamps().add(new WordTimestamp("x", 0, 0, 0, 0, 0)));
    }
}
