package com.kugelaudio.sdk;

import java.util.List;

/**
 * Callbacks for multi-context streaming sessions.
 * Each callback includes a contextId to identify which speaker/context the event belongs to.
 */
public interface MultiContextCallbacks {

    /** Called for each audio chunk received for a context. */
    void onChunk(String contextId, AudioChunk chunk);

    /** Called when a context has been created on the server. */
    default void onContextCreated(String contextId) {}

    /** Called when generation starts for a context. */
    default void onGenerationStarted(String contextId) {}

    /** Called when audio generation completes for a context (is_final). */
    default void onContextComplete(String contextId) {}

    /** Called when a context is closed. */
    default void onContextClosed(String contextId) {}

    /** Called when the entire session is closed. */
    default void onSessionClosed() {}

    /** Called when word timestamps are received for a context. */
    default void onWordTimestamps(String contextId, List<WordTimestamp> timestamps) {}

    /** Called if an error occurs. */
    default void onError(String contextId, KugelAudioException error) {}
}
