package com.kugelaudio.sdk;

import java.util.Base64;

/**
 * A single audio chunk received during streaming TTS.
 * Contains raw PCM16 (signed 16-bit little-endian) audio data.
 */
public final class AudioChunk {

    private final byte[] audio;
    private final int index;
    private final int sampleRate;
    private final int samples;

    public AudioChunk(byte[] audio, int index, int sampleRate, int samples) {
        this.audio = audio;
        this.index = index;
        this.sampleRate = sampleRate;
        this.samples = samples;
    }

    /**
     * Decodes a server JSON message into an AudioChunk.
     * The server sends audio as base64-encoded PCM16 data.
     */
    public static AudioChunk fromServerMessage(String audioBase64, int index, int sampleRate, int samples) {
        byte[] decoded = Base64.getDecoder().decode(audioBase64);
        return new AudioChunk(decoded, index, sampleRate, samples);
    }

    /** Raw PCM16 audio bytes (signed 16-bit little-endian, mono). */
    public byte[] getAudio() { return audio; }

    /** Chunk index in the stream. */
    public int getIndex() { return index; }

    /** Sample rate in Hz (e.g. 24000). */
    public int getSampleRate() { return sampleRate; }

    /** Number of PCM samples in this chunk. */
    public int getSamples() { return samples; }

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
}
