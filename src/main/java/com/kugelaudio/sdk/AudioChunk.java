package com.kugelaudio.sdk;

import java.util.Base64;

/**
 * A single audio chunk received during streaming TTS.
 * Contains raw audio data in the server-reported encoding.
 */
public final class AudioChunk {

    public static final String PCM_S16LE = "pcm_s16le";

    private final byte[] audio;
    private final String encoding;
    private final int index;
    private final int sampleRate;
    private final int samples;

    public AudioChunk(byte[] audio, int index, int sampleRate, int samples) {
        this(audio, PCM_S16LE, index, sampleRate, samples);
    }

    public AudioChunk(byte[] audio, String encoding, int index, int sampleRate, int samples) {
        this.audio = audio;
        this.encoding = encoding == null || encoding.isBlank() ? PCM_S16LE : encoding;
        this.index = index;
        this.sampleRate = sampleRate;
        this.samples = samples;
    }

    /**
     * Decodes a server JSON message into an AudioChunk.
     * The server sends audio as base64-encoded bytes.
     */
    public static AudioChunk fromServerMessage(String audioBase64, int index, int sampleRate, int samples) {
        return fromServerMessage(audioBase64, PCM_S16LE, index, sampleRate, samples);
    }

    public static AudioChunk fromServerMessage(String audioBase64, String encoding, int index, int sampleRate, int samples) {
        byte[] decoded = Base64.getDecoder().decode(audioBase64);
        return new AudioChunk(decoded, encoding, index, sampleRate, samples);
    }

    /** Raw audio bytes in {@link #getEncoding()}'s format. */
    public byte[] getAudio() { return audio; }

    /** Wire encoding, e.g. {@code pcm_s16le}, {@code mulaw}, or {@code alaw}. */
    public String getEncoding() { return encoding; }

    /** Chunk index in the stream. */
    public int getIndex() { return index; }

    /** Sample rate in Hz (e.g. 24000). */
    public int getSampleRate() { return sampleRate; }

    /** Number of samples in this chunk. For G.711 this equals byte length. */
    public int getSamples() { return samples; }

    /**
     * Converts PCM16 data to float32 samples normalized to [-1.0, 1.0].
     */
    public float[] toFloat32() {
        if (!PCM_S16LE.equals(encoding)) {
            throw new IllegalStateException("toFloat32 requires pcm_s16le audio; got " + encoding);
        }
        int numSamples = audio.length / 2;
        float[] result = new float[numSamples];
        for (int i = 0; i < numSamples; i++) {
            short sample = (short) ((audio[i * 2 + 1] << 8) | (audio[i * 2] & 0xFF));
            result[i] = sample / 32768.0f;
        }
        return result;
    }
}
