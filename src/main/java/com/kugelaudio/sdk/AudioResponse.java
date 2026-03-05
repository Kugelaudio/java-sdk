package com.kugelaudio.sdk;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Complete audio response assembled from streaming chunks.
 * Contains the concatenated PCM16 audio and optional metadata.
 */
public final class AudioResponse {

    private final byte[] audio;
    private final int sampleRate;
    private final int totalSamples;
    private final double durationMs;
    private final double generationMs;
    private final double rtf;
    private final List<WordTimestamp> wordTimestamps;

    private AudioResponse(Builder builder) {
        this.audio = builder.audio;
        this.sampleRate = builder.sampleRate;
        this.totalSamples = builder.totalSamples;
        this.durationMs = builder.durationMs;
        this.generationMs = builder.generationMs;
        this.rtf = builder.rtf;
        this.wordTimestamps = Collections.unmodifiableList(builder.wordTimestamps);
    }

    /** Raw PCM16 audio bytes (signed 16-bit little-endian, mono). */
    public byte[] getAudio() { return audio; }

    /** Sample rate in Hz. */
    public int getSampleRate() { return sampleRate; }

    /** Total number of PCM samples. */
    public int getTotalSamples() { return totalSamples; }

    /** Audio duration in milliseconds. */
    public double getDurationMs() { return durationMs; }

    /** Server-side generation time in milliseconds. */
    public double getGenerationMs() { return generationMs; }

    /** Real-time factor (generation_time / audio_duration). Lower is faster. */
    public double getRtf() { return rtf; }

    /** Word-level timing data (empty if not requested). */
    public List<WordTimestamp> getWordTimestamps() { return wordTimestamps; }

    /**
     * Converts PCM16 data to float32 samples normalized to [-1.0, 1.0].
     */
    public float[] toFloat32() {
        int numSamples = audio.length / 2;
        float[] result = new float[numSamples];
        for (int i = 0; i < numSamples; i++) {
            short sample = (short) ((audio[i * 2 + 1] << 8) | (audio[i * 2] & 0xFF));
            result[i] = sample / 32768.0f;
        }
        return result;
    }

    /**
     * Saves the audio as a WAV file.
     */
    public void saveWav(Path path) throws IOException {
        AudioFormats.writePcm16Wav(path, audio, sampleRate, (short) 1);
    }

    /**
     * Creates a WAV byte array suitable for streaming or in-memory processing.
     */
    public byte[] toWavBytes() {
        int dataLen = audio.length;
        ByteBuffer buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes());
        buf.putInt(36 + dataLen);
        buf.put("WAVE".getBytes());
        buf.put("fmt ".getBytes());
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) 1);
        buf.putInt(sampleRate);
        buf.putInt(sampleRate * 2);
        buf.putShort((short) 2);
        buf.putShort((short) 16);
        buf.put("data".getBytes());
        buf.putInt(dataLen);
        buf.put(audio);
        return buf.array();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private byte[] audio = new byte[0];
        private int sampleRate = 24000;
        private int totalSamples;
        private double durationMs;
        private double generationMs;
        private double rtf;
        private final List<WordTimestamp> wordTimestamps = new ArrayList<>();

        public Builder audio(byte[] audio) { this.audio = audio; return this; }
        public Builder sampleRate(int sampleRate) { this.sampleRate = sampleRate; return this; }
        public Builder totalSamples(int totalSamples) { this.totalSamples = totalSamples; return this; }
        public Builder durationMs(double durationMs) { this.durationMs = durationMs; return this; }
        public Builder generationMs(double generationMs) { this.generationMs = generationMs; return this; }
        public Builder rtf(double rtf) { this.rtf = rtf; return this; }
        public Builder addWordTimestamps(List<WordTimestamp> timestamps) {
            this.wordTimestamps.addAll(timestamps);
            return this;
        }

        public AudioResponse build() {
            return new AudioResponse(this);
        }
    }
}
