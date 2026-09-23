package com.kugelaudio.sdk;

import com.kugelaudio.sdk.internal.Diagnostics;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StreamingSession} that don't require a live server.
 *
 * <p>The full barge-in ack flow ({@code cancelCurrent()} → server
 * {@code interrupted} frame → {@link StreamCallbacks#onInterrupted()}) is
 * exercised by the live test suite; these cover the parts testable without a
 * WebSocket: the not-connected guard and the {@code onInterrupted} callback
 * contract (KUG-1050).
 */
class StreamingSessionTest {

    private StreamingSession newUnconnectedSession(StreamCallbacks callbacks) {
        KugelAudioOptions options = KugelAudioOptions.builder("test-key").build();
        StreamConfig config = StreamConfig.builder().voiceId(1).build();
        // Constructor does not connect — `ws` stays null until connect().
        return new StreamingSession(
                options, HttpClient.newHttpClient(), config, callbacks, Diagnostics.disabled());
    }

    @Test
    void cancelCurrentBeforeConnectIsNoOp() {
        StreamingSession session = newUnconnectedSession(new StreamCallbacks() {
            @Override public void onChunk(AudioChunk chunk) {}
        });
        // No socket yet → must return immediately without throwing or blocking.
        assertDoesNotThrow(session::cancelCurrent);
    }

    @Test
    void onInterruptedIsOverridable() {
        AtomicBoolean fired = new AtomicBoolean(false);
        StreamCallbacks callbacks = new StreamCallbacks() {
            @Override public void onChunk(AudioChunk chunk) {}
            @Override public void onInterrupted() { fired.set(true); }
        };
        callbacks.onInterrupted();
        assertTrue(fired.get(), "onInterrupted override should be invoked");
    }
}
