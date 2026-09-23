package com.kugelaudio.sdk;

import com.kugelaudio.sdk.internal.Diagnostics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RequestHandler.awaitCompletion must surface the typed SDK exception set
 * via completeExceptionally (error frame / WS close code) instead of the
 * raw ExecutionException — otherwise every server-side typed error
 * degrades to ConnectionException("Streaming failed: ...") in stream()
 * and generate() (KUG-1233: bad voice_id surfaced as ConnectionException
 * instead of NotFoundException).
 */
class RequestHandlerTest {

    private static final StreamCallbacks NOOP = new StreamCallbacks() {
        @Override
        public void onChunk(AudioChunk chunk) {}
    };

    private static final Diagnostics.Operation OP =
            Diagnostics.disabled().startOperation(Diagnostics.OP_STREAM, Diagnostics.TRANSPORT_WEBSOCKET);

    @Test
    void unwrapsTypedExceptionFromCompletion() {
        TTSResource.RequestHandler handler = new TTSResource.RequestHandler(NOOP, OP);
        NotFoundException notFound = new NotFoundException();
        handler.completion.completeExceptionally(notFound);

        NotFoundException thrown = assertThrows(
                NotFoundException.class,
                () -> handler.awaitCompletion(Duration.ofSeconds(1)));
        assertSame(notFound, thrown);
    }

    @Test
    void leavesForeignExceptionsWrapped() {
        TTSResource.RequestHandler handler = new TTSResource.RequestHandler(NOOP, OP);
        handler.completion.completeExceptionally(new IllegalStateException("boom"));

        ExecutionException thrown = assertThrows(
                ExecutionException.class,
                () -> handler.awaitCompletion(Duration.ofSeconds(1)));
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

    @Test
    void timesOutWhenNeverCompleted() {
        TTSResource.RequestHandler handler = new TTSResource.RequestHandler(NOOP, OP);
        assertThrows(
                TimeoutException.class,
                () -> handler.awaitCompletion(Duration.ofMillis(50)));
    }
}
