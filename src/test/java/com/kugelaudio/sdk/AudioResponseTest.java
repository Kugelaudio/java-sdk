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
    void carriesPerRequestUsage() {
        SessionUsage usage = new SessionUsage(5.4, 0.49, "eur", 12, "kugel-3");
        AudioResponse response = AudioResponse.builder()
                .audio(new byte[2])
                .usage(usage)
                .build();

        assertNotNull(response.getUsage());
        assertEquals(5.4, response.getUsage().getAudioSeconds());
        assertEquals(0.49, response.getUsage().getCostCents());
        assertTrue(response.getUsage().isCostAvailable());
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

    @Test
    void pcmHelpersRejectNonPcmAudio() {
        AudioResponse response = AudioResponse.builder()
                .audio(new byte[] {(byte) 0xD5})
                .encoding("alaw")
                .sampleRate(8000)
                .totalSamples(1)
                .build();

        assertEquals("alaw", response.getEncoding());
        assertThrows(IllegalStateException.class, response::toFloat32);
        assertThrows(IllegalStateException.class, response::toWavBytes);
    }
}
