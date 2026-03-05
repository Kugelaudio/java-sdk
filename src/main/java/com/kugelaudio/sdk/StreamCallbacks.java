package com.kugelaudio.sdk;

import java.util.List;

/**
 * Callbacks for streaming TTS. Implement to receive audio chunks as they arrive.
 *
 * <pre>{@code
 * client.tts().stream(request, new StreamCallbacks() {
 *     @Override
 *     public void onChunk(AudioChunk chunk) {
 *         // Process PCM16 audio bytes
 *     }
 *
 *     @Override
 *     public void onComplete(AudioResponse response) {
 *         response.saveWav(Path.of("output.wav"));
 *     }
 * });
 * }</pre>
 */
public interface StreamCallbacks {

    /** Called for each audio chunk received from the server. */
    void onChunk(AudioChunk chunk);

    /** Called when all audio has been received, with the assembled full response. */
    default void onComplete(AudioResponse response) {}

    /** Called when word timestamps are received (if requested). */
    default void onWordTimestamps(List<WordTimestamp> timestamps) {}

    /** Called if an error occurs during streaming. */
    default void onError(KugelAudioException error) {}
}
