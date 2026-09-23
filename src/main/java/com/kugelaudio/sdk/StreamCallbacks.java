package com.kugelaudio.sdk;

import java.util.List;

/**
 * Callbacks for streaming TTS. Implement to receive audio chunks as they arrive.
 *
 * <p>This interface is used for both:
 * <ul>
 *   <li>{@code client.tts().stream()} — single-request streaming via {@code /ws/tts}</li>
 *   <li>{@code client.tts().streamingSession()} — multi-send streaming via {@code /ws/tts/stream}</li>
 * </ul>
 *
 * <p>For single-request streaming, {@link #onComplete} fires after all audio arrives.
 * For streaming sessions, {@link #onChunkComplete} fires after each flushed segment and
 * {@link #onSessionClosed} fires when the session ends — {@link #onComplete} is not called.
 *
 * <pre>{@code
 * client.tts().stream(request, new StreamCallbacks() {
 *     @Override
 *     public void onChunk(AudioChunk chunk) {
 *         // Process chunk.getAudio() according to chunk.getEncoding()
 *     }
 *
 *     @Override
 *     public void onComplete(AudioResponse response) {
 *         response.saveWav(Path.of("output.wav")); // PCM output only
 *     }
 * });
 * }</pre>
 */
public interface StreamCallbacks {

    /** Called for each audio chunk received from the server. */
    void onChunk(AudioChunk chunk);

    /**
     * Called when all audio has been received for a single-request stream ({@code /ws/tts}).
     * Not called for streaming sessions — use {@link #onChunkComplete} and
     * {@link #onSessionClosed} instead.
     */
    default void onComplete(AudioResponse response) {}

    /**
     * Called after each flushed text segment completes in a streaming session
     * ({@code /ws/tts/stream}). Carries the chunk ID and total audio duration for that segment.
     */
    default void onChunkComplete(int chunkId, double audioSeconds, double genMs) {}

    /**
     * Called when a streaming session ({@code /ws/tts/stream}) is fully closed.
     * Equivalent to {@link #onComplete} for multi-send sessions.
     */
    default void onSessionClosed(double totalAudioSeconds, int totalTextChunks, int totalAudioChunks) {}

    /** Called when generation starts for a text segment (streaming session only). */
    default void onGenerationStarted(int chunkId, String text) {}

    /**
     * Called when the server acknowledges a barge-in
     * ({@link StreamingSession#cancelCurrent()}). After this fires, no further
     * audio chunks from the cancelled turn will arrive and the session is ready
     * for the next {@code send()}.
     */
    default void onInterrupted() {}

    /** Called when word timestamps are received (if requested). */
    default void onWordTimestamps(List<WordTimestamp> timestamps) {}

    /** Called if an error occurs during streaming. */
    default void onError(KugelAudioException error) {}
}
